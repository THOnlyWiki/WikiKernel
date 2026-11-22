package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.impl.PageReportQueries;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PagesWithPropSpecialPage extends PageListReport {
    private final PageReportQueries queries;

    PagesWithPropSpecialPage(MessageSource messages, PageReportQueries queries) {
        super(messages);
        this.queries = queries;
    }

    @Override
    public String name() {
        return "有特定页面属性的页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("PagesWithProp", "有特定頁面屬性的頁面");
    }

    @Override
    protected String messageKey() {
        return "pagesWithProp";
    }

    @Override
    protected String body(SpecialPageRequest request) {
        var params = request.parameters();
        String name = Strings.trimToEmpty(params.get("propname"));
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        String namespace = namespace(params.get("namespace"), "all");
        boolean reverse = checked(request, "reverse");
        boolean sortByValue = checked(request, "sortbyvalue");
        StringBuilder options = new StringBuilder("<datalist id=\"report-properties\">");
        for (String key : queries.propertyNames(request.user())) {
            options.append("<option value=\"").append(escape(key)).append("\"></option>");
        }
        String html = intro() + formStart()
                + input("propname", "report.pagesWithProp.name", name, " list=\"report-properties\" maxlength=\"64\"")
                + options.append("</datalist>") + namespaceSelect(namespace, true)
                + checkbox("reverse", "report.pagesWithProp.reverse", reverse)
                + checkbox("sortbyvalue", "report.pagesWithProp.sortByValue", sortByValue)
                + submit("report.linkSearch.submit");
        if (name.isEmpty()) {
            return html;
        }
        int start = offset(request);
        var result = queries.withProperty(name, namespace, reverse, sortByValue, start, REPORT_LIMIT, request.user());
        return html + results(result, start, Map.of("propname", name, "namespace", namespace,
                "reverse", reverse ? "1" : "0", "sortbyvalue", sortByValue ? "1" : "0"),
                row -> link(row.title()) + propertyValue(row.value()));
    }

    private String propertyValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > 255 || value.chars().anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c))) {
            return " (" + escape(message("report.pagesWithProp.longValue")) + ")";
        }
        return " <code>" + escape(value) + "</code>";
    }
}
