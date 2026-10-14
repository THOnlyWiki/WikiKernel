package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:前缀索引?prefix=前缀} / {@code Special:PrefixIndex}：列出以该前缀开头的页面。
 */
@Component
public class PrefixIndexSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 500;

    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;

    public PrefixIndexSpecialPage(MessageSource messageSource,
                                  WikiPageMapper wikiPageMapper,
                                  AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "前缀索引";
    }

    @Override
    public List<String> aliases() {
        return List.of("PrefixIndex");
    }

    @Override
    public String messageKey() {
        return "prefixIndex";
    }

    @Override
    public String category() {
        return SpecialPageCategories.PAGES;
    }

    @Override
    public String render(SpecialPageRequest request) {
        String prefix = WikiTitles.normalize(Strings.trimToEmpty(request.parameters().get("prefix")));
        String namespaceId = Strings.trimToEmpty(request.parameters().get("namespace"));
        String form = form(prefix, namespaceId);
        if (prefix.isEmpty()) {
            return page(form + "<p class=\"report-empty\">" + escape(message("report.prefixIndex.hint")) + "</p>");
        }
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .likeRight(WikiPage::getTitle, prefix)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            if (namespaceId.isEmpty()
                    || WikiNamespaces.byId(namespaceId)
                            .map(namespace -> WikiNamespaces.matches(page.getTitle(), namespace))
                            .orElse(true)) {
                items.add(link(page.getTitle()));
            }
        }
        return page(form + list(items));
    }

    private String form(String prefix, String namespaceId) {
        StringBuilder html = new StringBuilder();
        html.append("<form class=\"wiki-form wiki-form-inline\" method=\"get\" action=\"")
                .append(escape(WikiUrls.pagePath("特殊:前缀索引"))).append("\">")
                .append("<label for=\"prefix\">").append(escape(message("report.prefixIndex.prefix"))).append("</label> ")
                .append("<input type=\"text\" id=\"prefix\" name=\"prefix\" value=\"").append(escape(prefix)).append("\"/>")
                .append(" <label for=\"namespace\">").append(escape(message("report.prefixIndex.namespace"))).append("</label> ")
                .append("<select id=\"namespace\" name=\"namespace\">");
        html.append("<option value=\"\"").append(namespaceId.isEmpty() ? " selected" : "").append(">")
                .append(escape(message("report.prefixIndex.allNamespaces"))).append("</option>");
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            html.append("<option value=\"").append(escape(namespace.id())).append("\"")
                    .append(namespace.id().equals(namespaceId) ? " selected" : "").append(">")
                    .append(escape(message(namespace.labelKey()))).append("</option>");
        }
        html.append("</select> <button type=\"submit\" class=\"wiki-button\">")
                .append(escape(message("report.prefixIndex.submit"))).append("</button></form>");
        return html.toString();
    }
}
