package cc.thonly.wikikernel.common.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 页面属性存储（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 对应表 {@code wiki_page_property}：渲染时通过扩展点写入的属性会随页面保存持久化；
 * 插件也可以直接用它保存自己的数据（如 HitCounters 的浏览计数）。
 */
public interface PagePropertyStore {

    /** 读取单个属性。 */
    Optional<String> get(long pageId, String name);

    /** 批量读取（{@code pageId → propName → value}；没有的键不出现）。 */
    Map<Long, Map<String, String>> batch(Collection<Long> pageIds, Collection<String> names);

    /** 读取一组页面的全部属性。 */
    Map<Long, Map<String, String>> all(Collection<Long> pageIds);

    /** 写入（覆盖）。 */
    void set(long pageId, String name, String value);

    /** 删除（不存在时忽略）。 */
    void remove(long pageId, String name);

    /** 删除该页面的全部属性（页面被删除时用）。 */
    void removeAll(long pageId);

    /**
     * 覆盖页面的「渲染产出属性」：写入 {@code rendered}，并删除该页不在其中的渲染属性。
     * 通过 {@link #registerDurable(String)} 标记的持久属性（如浏览计数）不会被删除。
     */
    void replaceRendered(long pageId, Map<String, String> rendered);

    /** 标记「持久属性」：不会被 {@link #replaceRendered} 清除（插件在启动时登记）。 */
    void registerDurable(String name);

    /** 数值自增（不存在则从 0 开始），返回自增后的值；非数值按 0 处理。 */
    long addToInt(long pageId, String name, long delta);

    /** 该属性全站数值合计（兼容 SQLite / MySQL / H2：Java 侧累加）。 */
    long sumInt(String name);

    /** 按数值倒序列出该属性（用于「最多浏览」），最多 {@code limit} 条。 */
    List<PagePropertyValue> listByIntDesc(String name, int limit);
}
