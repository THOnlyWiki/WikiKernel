package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.service.SearchStore;
import cc.thonly.wikikernel.search.SearchService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索桥：把 core 的 {@code SearchService}（内置提供方 + 插件提供方）暴露给插件。
 */
@Service
public class SearchStoreImpl implements SearchStore {

    private final SearchService searchService;

    public SearchStoreImpl(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public List<SearchHit> search(String keyword, int limit) {
        return searchService.search(keyword == null ? "" : keyword, limit);
    }
}
