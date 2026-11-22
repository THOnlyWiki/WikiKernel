package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.impl.PageReportQueries;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PrefixIndexSpecialPage extends PageListReport {
    private final PageReportQueries queries;

    PrefixIndexSpecialPage(MessageSource messages, PageReportQueries queries) {
        super(messages);
        this.queries = queries;
    }

    @Override
    public String name() {
        return "前缀索引";
    }

    @Override
    public List<String> aliases() {
        return List.of("PrefixIndex", "所有有特定前缀的页面", "有特定前缀的所有页面", "所有有特定前綴的頁面");
    }

    @Override
    public String messageKey() {
        return "prefixIndex";
    }

    @Override
    public String subpageParameter() {
        return "prefix";
    }

    @Override
    protected String body(SpecialPageRequest request) {
        var params = request.parameters();
        String namespace = namespace(params.get("namespace"), "main");
        String requestedPrefix = WikiTitles.normalize(params.getOrDefault("prefix", params.get("page")));
        var fromPrefix = WikiNamespaces.all().stream().filter(ns -> !ns.prefixes().isEmpty())
                .filter(ns -> WikiNamespaces.matches(requestedPrefix, ns)).findFirst();
        String prefix = fromPrefix.isEmpty() ? requestedPrefix
                : requestedPrefix.substring(requestedPrefix.indexOf(':') + 1).stripLeading();
        if (fromPrefix.isPresent()) {
            namespace = fromPrefix.get().id();
        }
        boolean hideRedirects = checked(request, "hideredirects");
        boolean stripPrefix = checked(request, "stripprefix");
        String html = intro() + formStart() + input("prefix", "report.prefixIndex.prefix", prefix, "")
                + namespaceSelect(namespace, true) + checkbox("hideredirects", "allPages.hideRedirects", hideRedirects)
                + checkbox("stripprefix", "report.prefixIndex.stripPrefix", stripPrefix)
                + submit("report.prefixIndex.submit");
        if (!params.containsKey("prefix") && !params.containsKey("page")) {
            return html;
        }
        int start = offset(request);
        var matches = queries.prefix(prefix, namespace, hideRedirects, start, REPORT_LIMIT, request.user());
        String activePrefix = prefix;
        return html + results(matches, start, Map.of("prefix", prefix, "namespace", namespace,
                "hideredirects", hideRedirects ? "1" : "0", "stripprefix", stripPrefix ? "1" : "0"),
                row -> link(row.title(), stripPrefix ? stripPrefix(row.title(), activePrefix) : PageReportQueries.bareTitle(row.title())));
    }

    private static String stripPrefix(String title, String prefix) {
        String bare = PageReportQueries.bareTitle(title);
        return bare.length() > prefix.length() ? bare.substring(prefix.length()) : bare;
    }
}
