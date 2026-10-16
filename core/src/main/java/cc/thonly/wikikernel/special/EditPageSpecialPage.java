package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:编辑页面/页面标题} / {@code Special:EditPage}：跳到该页面的编辑界面
 * （不存在则显示创建编辑页的入口，与 MediaWiki 一致）。
 */
@Component
public class EditPageSpecialPage extends AbstractReportPage {

    public EditPageSpecialPage(MessageSource messageSource) {
        super(messageSource);
    }

    @Override
    public String name() {
        return "编辑页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("EditPage", "Edit");
    }

    @Override
    public String messageKey() {
        return "editPage";
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGETOOLS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String page = WikiTitles.normalize(Strings.trimToEmpty(request.parameters().get("page")));
        if (page.isEmpty()) {
            model.put("contentHtml", "<p class=\"report-empty\">"
                    + escape(message("report.editPage.hint")) + "</p>");
            return;
        }
        model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath(page) + "?action=edit");
    }
}
