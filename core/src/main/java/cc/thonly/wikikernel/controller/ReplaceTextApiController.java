package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 替换文本 API（{@code @RestController}）：把「特殊:替换文本」里勾选的页面正文批量查找并替换。
 * <p>
 * 只有管理员 / 行政员可用；逐条按 {@link AccessAction#EDIT} 校验编辑权限（无权限的页面跳过并计入失败），
 * 替换后逐页保存并写入编辑摘要（编辑日志由 {@link WikiPageService#editPage} 自动记录）。
 */
@RestController
@RequestMapping("/api/replace-text")
public class ReplaceTextApiController {

    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public ReplaceTextApiController(WikiPageService wikiPageService,
                                    AccessControlService accessControlService,
                                    PermissionService permissionService,
                                    MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    /**
     * 应用替换：{@code titles}（可重复）、{@code search}、{@code replace}、{@code regex}（1 = 正则）。
     * <p>
     * literal 用 {@link String#replace(CharSequence, CharSequence)}；正则用
     * {@link Matcher#replaceAll(String)}（替换串支持 {@code $1} 反向引用）。
     */
    @PostMapping("/apply")
    public AjaxResult<Map<String, Object>> apply(@RequestParam(value = "search", required = false) String search,
                                                 @RequestParam(value = "replace", required = false) String replace,
                                                 @RequestParam(value = "regex", required = false) String regex,
                                                 @RequestParam(value = "titles", required = false) List<String> titles,
                                                 HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("replaceText.noPermission"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("replaceText.noPermission"));
        }
        String needle = Strings.trimToEmpty(search);
        String replacement = Strings.trimToEmpty(replace);
        if (needle.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("replaceText.needQuery"));
        }
        List<String> targets = titles == null ? List.of() : titles.stream()
                .map(Strings::trimToEmpty)
                .filter(title -> !title.isEmpty())
                .toList();
        if (targets.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("replaceText.selectedNone"));
        }
        Pattern pattern = null;
        if (isOn(regex)) {
            try {
                pattern = Pattern.compile(needle);
            } catch (PatternSyntaxException exception) {
                return AjaxResult.error(AjaxCode.BAD_REQUEST,
                        message("replaceText.invalidRegex", exception.getMessage()));
            }
        }

        String summary = messageSource.getMessage("replaceText.summaryText",
                new Object[]{needle, replacement}, LocaleContextHolder.getLocale());
        int replaced = 0;
        int skipped = 0;
        for (String title : targets) {
            if (!accessControlService.can(AccessAction.EDIT, title, user)) {
                skipped++; // 没有编辑权限：跳过
                continue;
            }
            Optional<WikiPage> page = wikiPageService.getByTitle(title);
            Optional<String> latest = page.flatMap(value -> wikiPageService.getLatestContent(value.getId()));
            if (latest.isEmpty()) {
                skipped++; // 页面不存在 / 没有正文：跳过
                continue;
            }
            String content = latest.get();
            String updated = applyReplace(content, needle, replacement, pattern);
            if (updated == null || updated.equals(content)) {
                skipped++; // 正文里已无匹配 / 替换后没有变化：跳过
                continue;
            }
            wikiPageService.editPage(user, title, updated, summary);
            replaced++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("replaced", replaced);
        data.put("skipped", skipped);
        return AjaxResult.success(message("replaceText.done", replaced, skipped), data);
    }

    /** 计算替换后的正文；正文里没有匹配时返回 null（调用方计为跳过）。 */
    private static String applyReplace(String content, String search, String replace, Pattern pattern) {
        if (pattern == null) {
            if (!content.contains(search)) {
                return null;
            }
            return content.replace(search, replace);
        }
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        try {
            return matcher.replaceAll(replace);
        } catch (RuntimeException exception) {
            return null; // 替换串里的 $n 引用了不存在的分组
        }
    }

    private static boolean isOn(String value) {
        return "1".equals(value) || "on".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
