package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 扩展点：搜索提供者。
 * <p>
 * core 聚合所有实现，对外提供 {@code GET /api/search?q=...}；插件可按需提供实现。
 */
public interface SearchProvider extends Extension {

    /** 提供者名称。 */
    String name();

    /**
     * 执行搜索。
     *
     * @param keyword 关键字
     * @param limit   最大返回数量，小于等于 0 时使用插件默认值
     */
    List<SearchHit> search(String keyword, int limit);
}
