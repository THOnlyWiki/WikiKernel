package cc.thonly.wikikernel.common.service;

import java.util.List;
import java.util.Optional;

/**
 * 页面访问桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 按标题 / ID 查页面、读最新正文、列出内容页面。
 */
public interface WikiPageStore {

    /** 按 ID 查页面（不存在 / 已删除返回空）。 */
    Optional<PageSnapshot> byId(long pageId);

    /** 按标题查页面（不区分大小写，找不到返回空）。 */
    Optional<PageSnapshot> byTitle(String title);

    /**
     * 按标题查**当前请求者可以查看**的页面（不区分大小写；不存在 / 已删除 / 已隐藏 / 无权查看返回空）。
     * <p>
     * core 实现按访问控制规则过滤（与浏览页面一致）。插件做内容嵌入
     * （如 {@code {{#widget:…}}}）时应当用它，避免把无权查看的页面内容带进正文。
     */
    default Optional<PageSnapshot> visibleByTitle(String title) {
        return byTitle(title);
    }

    /** 最新正文（页面不存在返回空）。 */
    Optional<String> content(long pageId);

    /**
     * 页面最新修订 ID（页面不存在 / 实现不提供时返回空）。
     * <p>
     * 插件做「按页面内容缓存」时可用它当缓存键（修订变化 = 内容变化），
     * 拿不到时退回比较正文内容。
     */
    default Optional<Long> latestRevisionId(long pageId) {
        return Optional.empty();
    }

    /** 未删除的内容命名空间页面（含重定向标记；排序：ID 升序）。 */
    List<PageSnapshot> contentPages();
}
