package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
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
 * {@code 特殊:删除页面?page=标题} / {@code Special:DeletePage}：
 * 删除页面的确认页（仿 MediaWiki 的 {@code Special:Delete}）。
 * 表单由 {@code /js/page-actions.js} 提交到 {@code POST /api/pages/delete}，
 * 成功后回到首页；删除会写入页面删除日志（管理员可从日志恢复）。
 */
@Component
public class DeletePageSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public DeletePageSpecialPage(WikiPageService wikiPageService,
                                 AccessControlService accessControlService,
                                 PermissionService permissionService,
                                 MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "删除页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.deletePage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("DeletePage", "Delete");
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGETOOLS;
    }

    @Override
    public String template() {
        return "special/delete-page";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String title = Strings.trimToEmpty(request.parameters().get(PARAM_PAGE));
        model.put("lookupName", title);
        if (title.isEmpty()) {
            return;
        }

        WikiPage page = wikiPageService.getByTitle(title).orElse(null);
        if (page == null) {
            model.put("pageNotFound", true);
            return;
        }

        WikiUser user = request.user();
        boolean canDelete = accessControlService.can(AccessAction.DELETE, page.getTitle(), user);
        // 受保护页面与删除 API / 移动一致：只有管理员能删除
        if (canDelete && Boolean.TRUE.equals(page.getProtectedPage())
                && (user == null || !permissionService.canManage(user.getId()))) {
            canDelete = false;
            model.put("protectedPage", true);
        }
        model.put("sourcePage", page);
        model.put("canDelete", canDelete);
        model.put("deleteForbidden", !canDelete && !Boolean.TRUE.equals(model.get("protectedPage")));
    }
}
