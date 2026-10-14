package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.ActionHookService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.RequestActionContext;
import cc.thonly.wikikernel.web.Viewers;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 页面管理 API（@RestController）：移动页面、保护/解除保护。
 * <p>
 * 规则：都要登录；受保护页面只有管理员能移动；保护状态只有管理员能改。
 */
@RestController
@RequestMapping("/api/pages")
public class PageApiController {

    private final WikiPageService wikiPageService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final WikitextRenderer wikitextRenderer;
    private final BlockService blockService;
    private final TitleBlacklistService titleBlacklistService;
    private final ActionHookService actionHookService;
    private final MessageSource messageSource;

    public PageApiController(WikiPageService wikiPageService,
                             PermissionService permissionService,
                             AccessControlService accessControlService,
                             WikitextRenderer wikitextRenderer,
                             BlockService blockService,
                             TitleBlacklistService titleBlacklistService,
                             ActionHookService actionHookService,
                             MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.wikitextRenderer = wikitextRenderer;
        this.blockService = blockService;
        this.titleBlacklistService = titleBlacklistService;
        this.actionHookService = actionHookService;
        this.messageSource = messageSource;
    }

    /** 移动页面：{@code from} 原标题，{@code to} 新标题，{@code leaveRedirect} 是否在旧标题留下 #REDIRECT。历史保留，旧标题释放。 */
    @PostMapping("/move")
    public AjaxResult<Map<String, Object>> move(@RequestParam("from") String from,
                                                @RequestParam("to") String to,
                                                @RequestParam(value = "leaveRedirect", required = false, defaultValue = "false")
                                                boolean leaveRedirect,
                                                HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiBlock> block = blockService.currentBlock(user);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, blockService.notice(block.get()));
        }
        String fromTitle = Strings.trimToEmpty(from);
        String toTitle = Strings.trimToEmpty(to);
        if (toTitle.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("move.newTitleRequired"));
        }
        // 目标标题非法（非法字符 / 过长 / 相对路径等）：直接拒绝（仿 MediaWiki「标题无效」）
        Optional<WikiTitles.TitleError> targetError = WikiTitles.validate(toTitle);
        if (targetError.isPresent()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    message(targetError.get().messageKey(), targetError.get().detail()));
        }
        if (toTitle.equals(fromTitle)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("move.sameTitle"));
        }

        WikiPage page = wikiPageService.getByTitle(fromTitle).orElse(null);
        if (page == null) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("error.pageMissing", fromTitle));
        }
        if (!accessControlService.can(AccessAction.MOVE, fromTitle, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("move.forbidden"));
        }
        // 目标标题也要有创建权限（防止把普通页面移进仅管理员可编辑的命名空间，如 微件:）
        if (!accessControlService.can(AccessAction.CREATE, toTitle, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("move.targetForbidden"));
        }
        // 禁用标题名单（WikiKernel:Titleblacklist）：目标标题被禁止则不能移动
        Optional<TitleBlacklistService.Violation> violation =
                titleBlacklistService.check(toTitle, TitleBlacklistService.Operation.MOVE, user);
        if (violation.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    titleBlacklistMessage(violation.get(), toTitle, "titleblacklist.forbidden.move"));
        }
        if (Boolean.TRUE.equals(page.getProtectedPage()) && !isAdmin(user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("move.protected"));
        }
        // 动作前钩子（ActionGuard，如插件版滥用过滤器的 action=move）：按 order 升序执行；
        // BLOCK = 拒绝移动并返回说明，WARN = 放行但在响应里带上 warnings
        ActionHookService.Decision moveDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, Viewers.of(user, permissionService), "move")
                .target(fromTitle)
                .data("from", fromTitle)
                .data("to", toTitle)
                .data("leaveRedirect", leaveRedirect)
                .build());
        if (moveDecision.blocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, moveDecision.message());
        }
        if (wikiPageService.getByTitle(toTitle).isPresent()) {
            return AjaxResult.error(AjaxCode.CONFLICT, message("move.targetExists", toTitle));
        }

        WikiPage moved = wikiPageService.movePage(user, fromTitle, toTitle, leaveRedirect);
        Map<String, Object> data = pageData(moved);
        if (!moveDecision.warnings().isEmpty()) {
            data.put("warnings", moveDecision.warnings());
        }
        return AjaxResult.success(message("move.success"), data);
    }

    /** 保护 / 解除保护：{@code title} 页面标题，{@code protected=true|false}。 */
    @PostMapping("/protect")
    public AjaxResult<Map<String, Object>> protect(@RequestParam("title") String title,
                                                   @RequestParam("protected") boolean protectedFlag,
                                                   HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!accessControlService.can(AccessAction.PROTECT, title, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("protect.forbidden"));
        }
        // 动作前钩子（ActionGuard，action=protect）
        ActionHookService.Decision protectDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, Viewers.of(user, permissionService), "protect")
                .target("特殊:保护页面")
                .data("title", Strings.trimToEmpty(title))
                .data("protected", protectedFlag)
                .build());
        if (protectDecision.blocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, protectDecision.message());
        }
        Optional<WikiPage> page = wikiPageService.setProtected(user, Strings.trimToEmpty(title), protectedFlag);
        if (page.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("error.pageMissing", title));
        }
        Map<String, Object> data = pageData(page.get());
        if (!protectDecision.warnings().isEmpty()) {
            data.put("warnings", protectDecision.warnings());
        }
        return AjaxResult.success(message(protectedFlag ? "protect.successOn" : "protect.successOff"), data);
    }

    /**
     * 编辑预览（编辑页「显示预览」）：服务端渲染 {@code content}，不落盘。
     * 需要登录，并且对该标题有编辑 / 创建权限（与打开编辑页一致）。
     */
    @PostMapping("/preview")
    public AjaxResult<Map<String, Object>> preview(@RequestParam("title") String title,
                                                   @RequestParam("content") String content,
                                                   HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiBlock> block = blockService.currentBlock(user);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, blockService.notice(block.get()));
        }
        String normalized = Strings.trimToEmpty(title);
        if (normalized.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("error.titleRequired"));
        }
        WikiPage existing = wikiPageService.getByTitle(normalized).orElse(null);
        AccessAction action = existing == null ? AccessAction.CREATE : AccessAction.EDIT;
        if (!accessControlService.can(action, normalized, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    message(existing == null ? "error.createForbidden" : "error.editForbidden"));
        }

        WikitextRenderer.RenderResult result = wikitextRenderer.renderWithMeta(content, normalized);
        StringBuilder html = new StringBuilder(result.html());
        appendPreviewCatlinks(html, result.categories());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("html", html.toString());
        return AjaxResult.success(data);
    }

    /** 预览里的分类栏（与阅读视图的 .catlinks 结构一致）。 */
    private void appendPreviewCatlinks(StringBuilder html, List<WikiLinkScanner.CategoryRef> categories) {
        if (categories.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder items = new StringBuilder();
        for (WikiLinkScanner.CategoryRef ref : categories) {
            if (!seen.add(ref.title())) {
                continue;
            }
            boolean red = wikiPageService.getVisibleByTitle(ref.title()).isEmpty();
            items.append("<span class=\"catlinks-item\"><a")
                    .append(red ? " class=\"new\"" : "")
                    .append(" href=\"").append(HtmlUtils.htmlEscape(WikiUrls.pagePath(ref.title()))).append("\">")
                    .append(HtmlUtils.htmlEscape(WikiLinkScanner.categoryName(ref.title())))
                    .append("</a></span>");
        }
        if (items.length() == 0) {
            return;
        }
        html.append("<div class=\"catlinks-block\"><div class=\"catlinks-spacer\"></div><div class=\"catlinks\">")
                .append("<span class=\"catlinks-label\">").append(HtmlUtils.htmlEscape(message("view.categories")))
                .append("</span>").append(items)
                .append("</div></div>");
    }

    private boolean isAdmin(WikiUser user) {
        return permissionService.has(user.getId(), PermissionService.GROUP_ADMIN);
    }

    /** 自定义错误提示（规则里的 {@code errmsg=消息键}）；缺失时回退到内置消息键。 */
    private String titleBlacklistMessage(TitleBlacklistService.Violation violation, String title, String defaultKey) {
        String customKey = Strings.trimToEmpty(violation.customMessageKey());
        if (!customKey.isEmpty()) {
            String custom = messageSource.getMessage(customKey, new Object[]{title, violation.pattern()},
                    null, LocaleContextHolder.getLocale());
            if (custom != null) {
                return custom;
            }
        }
        return message(defaultKey, title);
    }

    private Map<String, Object> pageData(WikiPage page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", page.getTitle());
        data.put("url", WikiUrls.pagePath(page.getTitle()));
        return data;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
