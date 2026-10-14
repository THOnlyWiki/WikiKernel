package cc.thonly.wikikernel.common.extension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ActionApiModule} 的请求上下文：参数、登录信息与页面 / 属性查询。
 */
public interface ActionApiRequest {

    /** 原始请求参数（{@code action} / {@code format} 等也在内，只读）。 */
    Map<String, String> params();

    /** 取参数（不存在返回空串）。 */
    default String param(String name) {
        String value = params().get(name);
        return value == null ? "" : value;
    }

    /** {@code formatversion}（1 或 2）。 */
    int formatVersion();

    /** 当前登录用户名（未登录返回空串）。 */
    String username();

    /** 是否已登录。 */
    boolean loggedIn();

    /** 当前登录用户的用户组（未登录为空集合）。 */
    Set<String> groups();

    /**
     * 按 {@code titles=} 风格解析页面（{@code |} 分隔，支持传入含缺失页面的标题）。
     * 返回顺序与输入一致；标题非法 / 不存在时 {@link ApiPageRef#missing()} 为 true。
     */
    List<ApiPageRef> resolvePages(String titles);

    /** 单个标题解析（更常用的简写；不存在返回 missing=true 的引用）。 */
    default ApiPageRef page(String title) {
        List<ApiPageRef> pages = resolvePages(title == null ? "" : title);
        return pages.isEmpty() ? new ApiPageRef(0, title == null ? "" : title, true, null) : pages.get(0);
    }

    /** 批量读取页面属性（{@code pageId → propName → value}；没有的键不出现）。 */
    Map<Long, Map<String, String>> properties(Collection<Long> pageIds, Collection<String> names);

    /** 读取一组页面的全部属性。 */
    Map<Long, Map<String, String>> properties(Collection<Long> pageIds);

    /** 页面最新 wikitext（页面不存在返回空）。 */
    Optional<String> content(long pageId);
}
