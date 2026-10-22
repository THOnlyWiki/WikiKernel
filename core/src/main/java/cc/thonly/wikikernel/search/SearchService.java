package cc.thonly.wikikernel.search;

import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.extension.SearchProvider;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 搜索聚合：core 自带提供者 + 插件贡献的 {@link SearchProvider}，按相关度排序取前 N 条。
 */
@Service
public class SearchService {

    private final List<SearchProvider> coreProviders;
    private final PluginManager pluginManager;

    public SearchService(List<SearchProvider> coreProviders, PluginManager pluginManager) {
        this.coreProviders = coreProviders;
        this.pluginManager = pluginManager;
    }

    public List<SearchHit> search(String keyword, int limit) {
        if (Strings.isBlank(keyword)) {
            return List.of();
        }
        int max = limit > 0 ? Math.min(limit, 100) : 20;
        List<SearchHit> hits = new ArrayList<>();
        for (SearchProvider provider : coreProviders) {
            hits.addAll(provider.search(keyword, max));
        }
        for (SearchProvider provider : pluginManager.extensions(SearchProvider.class)) {
            hits.addAll(provider.search(keyword, max));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(max)
                .toList();
    }
}
