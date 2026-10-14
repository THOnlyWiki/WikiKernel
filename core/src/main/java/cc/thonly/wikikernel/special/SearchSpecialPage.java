package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.search.SearchService;
import cc.thonly.wikikernel.service.AccessControlService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:搜索} / {@code Special:Search}：Vector 顶栏搜索框的落地页。
 * 结果按访问规则过滤（无权查看的页面不出现，标题也不泄露）。
 */
@Component
public class SearchSpecialPage implements SpecialPage {

    private final SearchService searchService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public SearchSpecialPage(SearchService searchService,
                             AccessControlService accessControlService,
                             MessageSource messageSource) {
        this.searchService = searchService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "搜索";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("search.heading", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Search");
    }

    @Override
    public String template() {
        return "special/search";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String q = request.parameters().getOrDefault("q", "");
        int limit = 20;
        if (request.user() != null && request.user().getSearchLimit() != null
                && request.user().getSearchLimit() > 0) {
            limit = request.user().getSearchLimit();
        }
        model.put("q", q);
        model.put("results", accessControlService.filterReadable(
                searchService.search(q, limit), hit -> hit.title(), request.user()));
    }
}
