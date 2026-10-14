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
 * {@code 特殊:差异/123} / {@code 特殊:差异/123/456} / {@code Special:Diff}（仿 MediaWiki）：
 * 跳到版本差异页；只给版本号时会自动查出所属页面。
 */
@Component
public class DiffSpecialPage extends AbstractReportPage {

    private final WikiPageService wikiPageService;
    private final WikiPageMapper wikiPageMapper;

    public DiffSpecialPage(MessageSource messageSource,
                           WikiPageService wikiPageService,
                           WikiPageMapper wikiPageMapper) {
        super(messageSource);
        this.wikiPageService = wikiPageService;
        this.wikiPageMapper = wikiPageMapper;
    }

    @Override
    public String name() {
        return "差异";
    }

    @Override
    public List<String> aliases() {
        return List.of("Diff");
    }

    @Override
    public String messageKey() {
        return "diff";
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGETOOLS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String diffValue = Strings.trimToEmpty(parameters.get("diff"));
        String oldValue = Strings.trimToEmpty(parameters.get("oldid"));
        String title = WikiTitles.normalize(Strings.trimToEmpty(parameters.get("title")));

        // 子页语法：特殊:差异/123 或 特殊:差异/123/456（旧版本/新版本）
        String spec = Strings.trimToEmpty(parameters.get("page"));
        if (diffValue.isEmpty() && !spec.isEmpty()) {
            int slash = spec.indexOf('/');
            if (slash < 0) {
                diffValue = spec.trim();
            } else {
                oldValue = spec.substring(0, slash).trim();
                diffValue = spec.substring(slash + 1).trim();
            }
        }
        Long diffId = parseId(diffValue);
        Long oldId = parseId(oldValue);
        if (diffId == null) {
            model.put("contentHtml", "<p class=\"report-empty\">"
                    + escape(message("report.diff.hint")) + "</p>");
            return;
        }
        if (title.isEmpty()) {
            title = titleOfRevision(oldId == null ? diffId : oldId);
        }
        if (title.isEmpty()) {
            model.put("contentHtml", "<p class=\"report-empty\">"
                    + escape(message("report.diff.hint")) + "</p>");
            return;
        }
        model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath(title) + "?diff=" + diffId
                + (oldId == null ? "" : "&oldid=" + oldId));
    }

    private String titleOfRevision(Long revisionId) {
        WikiRevision revision = wikiPageService.getRevision(revisionId).orElse(null);
        WikiPage page = revision == null ? null : wikiPageMapper.selectById(revision.getPageId());
        return page == null ? "" : page.getTitle();
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
