package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.actionapi.ActionApiNamespaces;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.PageReportStore.Results;
import cc.thonly.wikikernel.common.service.PageReportStore.Row;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.web.WikiNamespaces;
import org.springframework.context.MessageSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

abstract class PageListReport extends AbstractReportPage {
    PageListReport(MessageSource messages) {
        super(messages);
    }

    @Override
    public final String category() {
        return SpecialPageCategories.PAGES;
    }

    @Override
    public final String template() {
        return "special/page-list-report";
    }

    @Override
    public final void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("reportHtml", body(request));
    }

    protected abstract String body(SpecialPageRequest request);

    protected String intro() {
        return "<p class=\"report-intro\">" + escape(message("report." + messageKey() + ".intro")) + "</p>";
    }

    protected String formStart() {
        return "<form method=\"get\" class=\"wiki-form wiki-filter-panel wiki-form-inline\" action=\""
                + escape(path()) + "\">";
    }

    protected String input(String name, String labelKey, String value, String extraAttributes) {
        return "<label for=\"report-" + name + "\">" + escape(message(labelKey)) + "</label> "
                + "<input type=\"text\" id=\"report-" + name + "\" name=\"" + name + "\" value=\""
                + escape(value) + "\"" + extraAttributes + "> ";
    }

    protected String checkbox(String name, String labelKey, boolean checked) {
        return "<label><input type=\"checkbox\" name=\"" + name + "\" value=\"1\""
                + (checked ? " checked" : "") + "> " + escape(message(labelKey)) + "</label> ";
    }

    protected String submit(String labelKey) {
        return "<button class=\"wiki-button wiki-button-primary\" type=\"submit\">" + escape(message(labelKey)) + "</button></form>";
    }

    protected String namespaceSelect(String selected, boolean includeAll) {
        StringBuilder html = new StringBuilder("<label for=\"report-namespace\">")
                .append(escape(message("allPages.namespaceLabel")))
                .append("</label> <select id=\"report-namespace\" name=\"namespace\">");
        if (includeAll) {
            html.append(option("all", "allPages.allNamespaces", selected));
        }
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            html.append(option(namespace.id(), namespace.labelKey(), selected));
        }
        return html.append("</select> ").toString();
    }

    private String option(String id, String labelKey, String selected) {
        return "<option value=\"" + escape(id) + "\"" + (id.equals(selected) ? " selected" : "") + ">"
                + escape(message(labelKey)) + "</option>";
    }

    protected String namespace(String requested, String fallback) {
        String value = Strings.trimToEmpty(requested);
        if ("all".equals(value) || "-1".equals(value)) {
            return "all";
        }
        return WikiNamespaces.byId(value).or(() -> WikiNamespaces.all().stream()
                .filter(ns -> value.equals(Integer.toString(ns.prefixes().isEmpty() ? 0
                        : ActionApiNamespaces.idOf(ns.prefixes().getFirst() + "Example"))))
                .filter(ns -> !ns.prefixes().isEmpty() || value.equals("0"))
                .findFirst()).map(WikiNamespaces.Namespace::id).orElse(fallback);
    }

    protected static boolean checked(SpecialPageRequest request, String key) {
        return "1".equals(request.parameters().get(key)) || "true".equalsIgnoreCase(request.parameters().get(key));
    }

    protected static int offset(SpecialPageRequest request) {
        try {
            return Math.max(0, Math.min(1_000_000, Integer.parseInt(request.parameters().get("offset"))));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    protected String results(Results result, int offset, Map<String, String> filters,
                             Function<Row, String> item) {
        StringBuilder pager = new StringBuilder();
        if (offset > 0) {
            pager.append(pageLink(filters, Math.max(0, offset - REPORT_LIMIT), "log.page.prev"));
        }
        if (result.more()) {
            pager.append(pageLink(filters, offset + REPORT_LIMIT, "log.page.next"));
        }
        String controls = pager.isEmpty() ? "" : "<nav class=\"allpages-nav\" data-wiki-query-links>" + pager + "</nav>";
        return controls + list(result.rows().stream().map(item).toList()) + controls;
    }

    private String path() {
        return WikiUrls.pagePath("特殊:" + name());
    }

    private String pageLink(Map<String, String> filters, int offset, String label) {
        StringBuilder url = new StringBuilder(path()).append("?offset=").append(offset);
        filters.forEach((key, value) -> url.append('&').append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return "<a href=\"" + escape(url.toString()) + "\">" + escape(message(label)) + "</a> ";
    }
}
