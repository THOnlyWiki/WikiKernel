package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.impl.PageReportQueries;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ListRedirectsSpecialPage extends PageListReport {
    private final PageReportQueries queries;

    ListRedirectsSpecialPage(MessageSource messages, PageReportQueries queries) {
        super(messages);
        this.queries = queries;
    }

    @Override
    public String name() {
        return "重定向列表";
    }

    @Override
    public List<String> aliases() {
        return List.of("ListRedirects");
    }

    @Override
    protected String messageKey() {
        return "listRedirects";
    }

    @Override
    protected String body(SpecialPageRequest request) {
        int start = offset(request);
        return intro() + results(queries.redirects(start, REPORT_LIMIT, request.user()), start, Map.of(),
                row -> "<a href=\"" + escape(WikiUrls.pagePath(row.title()) + "?redirect=no") + "\">"
                        + escape(row.title()) + "</a> → " + link(row.value()));
    }
}
