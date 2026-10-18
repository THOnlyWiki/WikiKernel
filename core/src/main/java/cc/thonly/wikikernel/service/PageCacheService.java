package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.service.PageCache;

import java.util.Optional;

/**
 * 页面正文缓存（core 内部接口，同时实现插件桥 {@link PageCache}）：
 * 缓存渲染后的正文 HTML，并在版本 / 模板 / 配置变化时失效。
 * <p>
 * 配置存站点设置 {@code wiki_setting}（键见本接口常量，{@code 特殊:权限配置 → 站点设置} 可改），
 * {@code LocalSettings.yaml} 的 {@code wikikernel.cache.page.*} 兜底；每次读写都重新取配置，
 * 因此<b>改完立即生效、无需重启</b>。
 * <p>
 * 后端优先级：Redis（配置了且连得上）→ 进程内 LRU（有界）→ 不缓存（{@code enabled()} 为 false，
 * 功能照常）。任何后端故障都只降级、不抛到业务路径上。
 */
public interface PageCacheService extends PageCache {

    /** 站点设置键：是否启用页面正文缓存（默认 false）。 */
    String ENABLED_KEY = "cache.page.enabled";

    /** 站点设置键：后端（auto / redis / memory / none，默认 auto）。 */
    String BACKEND_KEY = "cache.page.backend";

    /** 站点设置键：条目有效期秒数（默认 300）。 */
    String TTL_SECONDS_KEY = "cache.page.ttl-seconds";

    /** 站点设置键：进程内 LRU 条目上限（默认 500）。 */
    String MAX_ENTRIES_KEY = "cache.page.max-entries";

    /** 站点设置键：模板变更后是否批量预渲染（默认 true）。 */
    String PRELOAD_ON_TEMPLATE_CHANGE_KEY = "cache.page.preload-on-template-change";

    /** 站点设置键：预渲染并发上限（默认 CPU 核数）。 */
    String PRELOAD_PARALLELISM_KEY = "cache.page.preload-parallelism";

    /** 站点设置键：单批预渲染页面数（默认 0 = 自动：并发上限 × 8）。 */
    String PRELOAD_BATCH_SIZE_KEY = "cache.page.preload-batch-size";

    /**
     * 取页面正文：读缓存 → 命中直接返回 → 未命中渲染后写缓存。
     * 缓存关闭 / 后端不可用时直接渲染（不写缓存）。
     *
     * @param page    页面实体（用它的标题与 {@code latestRevisionId} 做键与版本校验）
     * @param content 最新版本正文
     */
    PageBody body(WikiPage page, String content);

    /**
     * 按「标题 + 版本」读缓存（不渲染）。版本号已知时用它，省一次页面查询。
     *
     * @param title          页面标题
     * @param latestRevisionId 页面当前最新版本号；{@code null} 视为未命中
     */
    Optional<PageBody> get(String title, Long latestRevisionId);

    /** 当前后端名：{@code memory} / {@code redis} / {@code none}（诊断与测试用；会触发一次探测）。 */
    String backendName();

    /**
     * 失效页面缓存，但保留已经是 {@code keepRevisionId} 版本的条目。
     * 用于保存后的后台清理：如果页面已经被重新渲染并写入了新版本，不必再把它删掉。
     *
     * @param title          页面标题
     * @param keepRevisionId 保存后的最新版本号；{@code null} 表示无条件失效
     */
    void invalidate(String title, Long keepRevisionId);

    /** 已完成预渲染的页面数（异步预渲染的观察点：日志与测试用）。 */
    long preloadedPages();
}
