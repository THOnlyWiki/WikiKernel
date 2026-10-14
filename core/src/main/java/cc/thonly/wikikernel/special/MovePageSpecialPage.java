package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:移动页面?page=标题} / {@code Special:MovePage}：把页面移动到新标题（历史保留）。
 * 表单由 {@code /js/page-actions.js} 提交到 {@code POST /api/pages/move}。
 */
@Component
public class MovePageSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    private final WikiPageService wikiPageService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public MovePageSpecialPage(WikiPageService wikiPageService,
                               PermissionService permissionService,
                               AccessControlService accessControlService,
                               MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "移动页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.movePage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("MovePage", "Move");
    }

    @Override
    public String template() {
        return "special/move-page";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String title = Strings.trimToEmpty(request.parameters().get(PARAM_PAGE));
        model.put("lookupName", title);
        if (title.isEmpty()) {
            return; // 显示“输入页面标题”查找框
        }

        WikiPage page = wikiPageService.getByTitle(title).orElse(null);
        if (page == null) {
            model.put("pageNotFound", true);
            return;
        }

        WikiUser user = request.user();
        boolean loginRequired = user == null;
        boolean adminCanOnly = Boolean.TRUE.equals(page.getProtectedPage())
                && (user == null || !permissionService.has(user.getId(), PermissionService.GROUP_ADMIN));
        boolean ruleAllows = accessControlService.can(AccessAction.MOVE, title, user);

        model.put("sourcePage", page);
        model.put("moveLogin", loginRequired);
        model.put("moveProtected", !loginRequired && adminCanOnly);
        model.put("moveForbidden", !loginRequired && !adminCanOnly && !ruleAllows);
        model.put("canMove", !loginRequired && !adminCanOnly && ruleAllows);
    }
}
