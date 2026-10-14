package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:重定向/类型/目标} / {@code Special:Redirect}（仿 MediaWiki）：
 * 支持 {@code user/用户名}、{@code page/标题}、{@code file/文件名}、{@code rev/版本ID} 四种类型。
 */
@Component
public class RedirectSpecialPage extends AbstractReportPage {

    private final WikiPageService wikiPageService;
    private final WikiPageMapper wikiPageMapper;

    public RedirectSpecialPage(MessageSource messageSource,
                               WikiPageService wikiPageService,
                               WikiPageMapper wikiPageMapper) {
        super(messageSource);
        this.wikiPageService = wikiPageService;
        this.wikiPageMapper = wikiPageMapper;
    }

    @Override
    public String name() {
        return "重定向";
    }

    @Override
    public List<String> aliases() {
        return List.of("Redirect");
    }

    @Override
    public String messageKey() {
        return "redirect";
    }

    @Override
    public String category() {
        return SpecialPageCategories.REDIRECTS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String spec = Strings.trimToEmpty(parameters.get("page"));
        if (spec.isEmpty()) {
            String type = Strings.trimToEmpty(parameters.get("type"));
            String target = Strings.trimToEmpty(parameters.get("target"));
            if (!type.isEmpty() && !target.isEmpty()) {
                spec = type + "/" + target;
            }
        }
        if (spec.isEmpty()) {
            model.put("contentHtml", hint());
            return;
        }
        int slash = spec.indexOf('/');
        String type = (slash < 0 ? spec : spec.substring(0, slash)).trim().toLowerCase(Locale.ROOT);
        String target = slash < 0 ? "" : spec.substring(slash + 1).trim();
        if (target.isEmpty()) {
            model.put("contentHtml", hint());
            return;
        }
        String path = switch (type) {
            case "user" -> WikiUrls.pagePath("用户:" + target);
            case "page" -> WikiUrls.pagePath(WikiTitles.normalize(target));
            case "file" -> WikiUrls.pagePath(FileTitles.titleOf(target));
            case "rev" -> revisionPath(target);
            default -> null;
        };
        if (path == null) {
            model.put("contentHtml", "<p class=\"report-empty\">"
                    + escape(message("report.redirect.unknownType", type)) + "</p>");
            return;
        }
        model.put(REDIRECT_MODEL_ATTRIBUTE, path);
    }

    /** {@code rev/123} → 该版本所属页面的固定链接。 */
    private String revisionPath(String value) {
        Long revisionId = parseId(value);
        Optional<WikiRevision> revision = revisionId == null
                ? Optional.empty() : wikiPageService.getRevision(revisionId);
        if (revision.isEmpty()) {
            return null;
        }
        WikiPage page = wikiPageMapper.selectById(revision.get().getPageId());
        return page == null ? null : WikiUrls.pagePath(page.getTitle()) + "?oldid=" + revisionId;
    }

    private String hint() {
        return "<p class=\"report-empty\">" + escape(message("report.redirect.hint")) + "</p>";
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
