package cc.thonly.wikikernel.plugin.disambiguator;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.PageReportStore;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class DisambiguationReport implements SpecialPage {
    private static final int LIMIT = 50;

    public record Item(String source, String sourceUrl, String target, String targetUrl, String editUrl) {}

    private final boolean backlinks;
    private final PageReportStore reports;
    private final AccessCheck access;
    private final MessageSource messages;

    DisambiguationReport(boolean backlinks, PageReportStore reports, AccessCheck access, MessageSource messages) {
        this.backlinks = backlinks;
        this.reports = reports;
        this.access = access;
        this.messages = messages;
    }

    @Override
    public String name() {
        return backlinks ? "链接至消歧义页面的页面" : "消歧义页面";
    }

    @Override
    public String displayName() {
        String key = backlinks ? "disambiguator.links" : "disambiguator.pages";
        return messages.getMessage(key, null, name(), LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return backlinks ? List.of("DisambiguationPageLinks", "連結至消歧義頁面的頁面")
                : List.of("DisambiguationPages", "消歧義頁面");
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGES;
    }

    @Override
    public String template() {
        return "special/disambiguation-report";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        int offset = offset(request.parameters().get("offset"));
        var results = backlinks
                ? reports.linkingToProperty(DisambiguatorPlugin.PROPERTY, offset, LIMIT, request.user())
                : reports.withProperty(DisambiguatorPlugin.PROPERTY, "all", false, false, offset, LIMIT, request.user());
        Viewer viewer = request.user() == null ? Viewer.ANONYMOUS
                : Viewer.of(request.user().getId(), request.user().getUsername(), Set.of());
        List<Item> items = results.rows().stream().map(row -> {
            String sourceUrl = WikiUrls.pagePath(row.title());
            String targetUrl = backlinks ? WikiUrls.pagePath(row.value()) : null;
            boolean edit = backlinks && access.can(AccessAction.EDIT, row.title(), viewer);
            return new Item(row.title(), sourceUrl, row.value(), targetUrl,
                    edit ? sourceUrl + "?action=edit" : null);
        }).toList();
        String path = WikiUrls.pagePath("特殊:" + name());
        model.put("disambiguationItems", items);
        model.put("disambiguationBacklinks", backlinks);
        model.put("disambiguationStart", offset + 1);
        model.put("disambiguationPrev", offset > 0 ? path + "?offset=" + Math.max(0, offset - LIMIT) : null);
        model.put("disambiguationNext", results.more() ? path + "?offset=" + (offset + LIMIT) : null);
    }

    private static int offset(String value) {
        try {
            return Math.max(0, Math.min(1_000_000, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
