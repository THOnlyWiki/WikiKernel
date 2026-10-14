package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:保护页面?page=标题} / {@code Special:ProtectPage}：
 * 设置 / 解除页面保护（只有管理员可以编辑受保护页面）。
 * 表单由 {@code /js/page-actions.js} 提交到 {@code POST /api/pages/protect}。
 */
@Component
public class ProtectPageSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public ProtectPageSpecialPage(WikiPageService wikiPageService,
                                  AccessControlService accessControlService,
                                  MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "保护页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.protectPage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("ProtectPage", "Protect");
    }

    @Override
    public String template() {
        return "special/protect-page";
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
        boolean canProtect = accessControlService.can(AccessAction.PROTECT, title, user);
        model.put("sourcePage", page);
        model.put("canProtect", canProtect);
        model.put("protectForbidden", !canProtect);
    }
}
