package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:固定链接/页面标题?oldid=ID} / {@code Special:PermanentLink}：
 * 跳到页面的指定版本（不传 oldid 时跳到当前版本；只知道版本号时会查出所属页面）。
 */
@Component
public class PermanentLinkSpecialPage extends AbstractReportPage {

    private final WikiPageService wikiPageService;
    private final WikiPageMapper wikiPageMapper;

    public PermanentLinkSpecialPage(MessageSource messageSource,
                                    WikiPageService wikiPageService,
                                    WikiPageMapper wikiPageMapper) {
        super(messageSource);
        this.wikiPageService = wikiPageService;
        this.wikiPageMapper = wikiPageMapper;
    }

    @Override
    public String name() {
        return "固定链接";
    }

    @Override
    public List<String> aliases() {
        return List.of("PermanentLink");
    }

    @Override
    public String messageKey() {
        return "permanentLink";
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGETOOLS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String page = WikiTitles.normalize(Strings.trimToEmpty(parameters.get("page")));
        Long oldId = parseId(parameters.get("oldid"));
        if (page.isEmpty() && oldId != null) {
            WikiRevision revision = wikiPageService.getRevision(oldId).orElse(null);
            WikiPage found = revision == null ? null : wikiPageMapper.selectById(revision.getPageId());
            page = found == null ? "" : found.getTitle();
        }
        if (page.isEmpty()) {
            model.put("contentHtml", "<p class=\"report-empty\">"
                    + escape(message("report.permanentLink.hint")) + "</p>");
            return;
        }
        model.put(REDIRECT_MODEL_ATTRIBUTE, oldId == null
                ? WikiUrls.pagePath(page)
                : WikiUrls.pagePath(page) + "?oldid=" + oldId);
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
