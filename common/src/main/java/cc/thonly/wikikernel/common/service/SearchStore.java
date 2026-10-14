package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.extension.SearchHit;

import java.util.List;

/**
 * 搜索桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 用站点当前的搜索引擎（core 内置提供方 + 插件注册的 {@code SearchProvider}）搜索页面。
 * <p>
 * 权限过滤由调用方按 {@link AccessCheck} 自己做（core 的搜索接口对匿名用户也只做同样的事），
 * 所以这里返回的是**未过滤**的命中列表。
 */
public interface SearchStore {

    /**
     * 搜索页面（标题 + 正文）。
     *
     * @param keyword 关键词（空白 = 返回空列表）
     * @param limit   最多返回多少条（&lt;= 0 = core 默认上限）
     */
    List<SearchHit> search(String keyword, int limit);
}
