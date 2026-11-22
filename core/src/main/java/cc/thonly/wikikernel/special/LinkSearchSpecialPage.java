package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.impl.PageReportQueries;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LinkSearchSpecialPage extends PageListReport {
    private final PageReportQueries queries;

    LinkSearchSpecialPage(MessageSource messages, PageReportQueries queries) {
        super(messages);
        this.queries = queries;
    }

    @Override
    public String name() {
        return "外部链接搜索";
    }

    @Override
    public List<String> aliases() {
        return List.of("LinkSearch", "外部連結搜尋", "外部連結搜索");
    }

    @Override
    protected String messageKey() {
        return "linkSearch";
    }

    @Override
    public String subpageParameter() {
        return "target";
    }

    @Override
    protected String body(SpecialPageRequest request) {
        var params = request.parameters();
        String target = Strings.trimToEmpty(params.get("target"));
        String namespace = namespace(params.get("namespace"), "all");
        String html = intro() + formStart()
                + input("target", "report.linkSearch.target", target, " maxlength=\"2048\"")
                + namespaceSelect(namespace, true) + submit("report.linkSearch.submit");
        if (target.isEmpty()) {
            return html;
        }
        ExternalLinkPattern pattern;
        try {
            pattern = ExternalLinkPattern.parse(target);
        } catch (IllegalArgumentException invalidPattern) {
            return html + "<p class=\"error\">" + escape(message("report.linkSearch.invalid")) + "</p>";
        }
        int start = offset(request);
        var matches = queries.externalLinks(pattern::matches, namespace, start, REPORT_LIMIT, request.user());
        return html + results(matches, start, Map.of("target", target, "namespace", namespace), row ->
                message("report.linkSearch.line", externalLink(row.value()), link(row.title())));
    }

    private String externalLink(String url) {
        return "<a class=\"external\" rel=\"nofollow\" href=\"" + escape(url) + "\">" + escape(url) + "</a>";
    }
}
