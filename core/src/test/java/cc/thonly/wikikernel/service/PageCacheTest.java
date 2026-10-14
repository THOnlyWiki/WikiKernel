package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面正文缓存（进程内 LRU 后端）：
 * 读 / 写 / 失效 / TTL / 按条数上限淘汰、版本安全（页面一改就不返回旧正文）、
 * 配置开关（enabled = false / backend = none 完全不缓存，且改完立即生效）、
 * 模板变更 → 失效所有嵌入页面并用虚拟线程批量预渲染。
 * <p>
 * Redis 后端见 {@link PageCacheRedisTest}（本机没有 Redis 时自动跳过）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PageCacheTest {

    @Autowired
    private PageCacheService pageCacheService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private SiteSettingsService siteSettingsService;

    /** 每个用例之后恢复默认配置，避免污染同一 Spring 上下文里的其它测试类。 */
    @AfterEach
    void restoreDefaults() {
        siteSettingsService.set(PageCacheService.ENABLED_KEY, "false");
        siteSettingsService.set(PageCacheService.BACKEND_KEY, "auto");
        siteSettingsService.set(PageCacheService.TTL_SECONDS_KEY, "300");
        siteSettingsService.set(PageCacheService.MAX_ENTRIES_KEY, "500");
        siteSettingsService.set(PageCacheService.PRELOAD_ON_TEMPLATE_CHANGE_KEY, "true");
        siteSettingsService.set(PageCacheService.PRELOAD_PARALLELISM_KEY, "");
        siteSettingsService.set(PageCacheService.PRELOAD_BATCH_SIZE_KEY, "");
    }

    @Test
    void memoryBackendCachesRenderedHtmlAndNeverReturnsStaleRevision() {
        configure("true", "memory");
        String title = "缓存正文页" + System.nanoTime();
        WikiPage created = wikiPageService.editPage(null, title, "'''缓存正文'''[[分类:缓存测试]]");
        String content = wikiPageService.getLatestContent(created.getId()).orElse("");

        // 未命中 → 渲染 → 写缓存：HTML 与渲染元信息（分类）一起进缓存
        PageBody rendered = pageCacheService.body(created, content);
        assertTrue(rendered.html().contains("<b>缓存正文</b>"), rendered.html());
        PageBody cached = pageCacheService.get(title, created.getLatestRevisionId()).orElseThrow();
        assertEquals(rendered.html(), cached.html());
        assertEquals(1, cached.categories().size());
        assertEquals("分类:缓存测试", cached.categories().get(0).title());
        // 插件桥：按标题读到的就是同一份 HTML
        assertTrue(pageCacheService.enabled());
        assertEquals(rendered.html(), pageCacheService.html(title).orElseThrow());

        // 命中：同样的版本再取，拿到的还是渲染结果
        WikiPage again = wikiPageService.getByTitle(title).orElseThrow();
        assertEquals(rendered.html(), pageCacheService.body(again, content).html());

        // 页面被编辑 → 新版本：旧条目不会被返回，重新渲染出新正文
        WikiPage edited = wikiPageService.editPage(null, title, "'''第二版正文'''");
        assertNotEquals(created.getLatestRevisionId(), edited.getLatestRevisionId());
        assertTrue(pageCacheService.get(title, edited.getLatestRevisionId()).isEmpty(),
                "旧版本的条目不能命中新版本");
        assertTrue(pageCacheService.html(title).isEmpty(), "编辑之后按标题也读不到旧正文");
        String latest = wikiPageService.getLatestContent(edited.getId()).orElse("");
        PageBody refreshed = pageCacheService.body(edited, latest);
        assertTrue(refreshed.html().contains("第二版正文"), refreshed.html());
        assertFalse(refreshed.html().contains("缓存正文</b>"), refreshed.html());
    }

    @Test
    void putFromBridgeKeepsMetadataAndHonoursRevision() {
        configure("true", "memory");
        String title = "缓存写入页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''桥写入正文'''[[分类:缓存测试]]");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        PageBody rendered = pageCacheService.body(page, content);

        // 同版本覆盖：HTML 被替换，渲染元信息（分类）保留
        pageCacheService.put(title, "<p>插件写入的正文</p>", page.getLatestRevisionId());
        PageBody replaced = pageCacheService.get(title, page.getLatestRevisionId()).orElseThrow();
        assertEquals("<p>插件写入的正文</p>", replaced.html());
        assertEquals(rendered.categories().size(), replaced.categories().size());

        // 版本对不上：写进去也不会被当作这个版本的正文
        pageCacheService.put(title, "<p>别的版本</p>", page.getLatestRevisionId() + 100L);
        assertTrue(pageCacheService.html(title).isEmpty(), "写的是别的版本，按标题读不到");
    }

    @Test
    void ttlExpiresEntries() throws InterruptedException {
        configure("true", "memory");
        siteSettingsService.set(PageCacheService.TTL_SECONDS_KEY, "1");
        String title = "缓存TTL页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "TTL 正文");
        pageCacheService.put(title, "<p>ttl</p>", page.getLatestRevisionId());
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent());

        Thread.sleep(1300L);
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isEmpty(),
                "超过 TTL 之后条目应该过期");
    }

    @Test
    void maxEntriesEvictsLeastRecentlyUsed() {
        configure("true", "memory");
        siteSettingsService.set(PageCacheService.MAX_ENTRIES_KEY, "2");
        pageCacheService.put("缓存上限页A", "<p>a</p>", 11L);
        pageCacheService.put("缓存上限页B", "<p>b</p>", 12L);
        // 读一次 A：A 变成最近使用，B 变成最久未使用
        assertTrue(pageCacheService.get("缓存上限页A", 11L).isPresent());
        pageCacheService.put("缓存上限页C", "<p>c</p>", 13L);

        assertTrue(pageCacheService.get("缓存上限页A", 11L).isPresent(), "最近使用的条目应保留");
        assertTrue(pageCacheService.get("缓存上限页C", 13L).isPresent());
        assertTrue(pageCacheService.get("缓存上限页B", 12L).isEmpty(), "最久未使用的条目应被淘汰");
    }

    @Test
    void enabledFalseMeansNoCachingAtAll() {
        String title = "缓存关闭页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''关闭时不缓存'''");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");

        configure("false", "memory");
        assertFalse(pageCacheService.enabled());
        assertEquals("none", pageCacheService.backendName());
        assertTrue(pageCacheService.body(page, content).html().contains("<b>关闭时不缓存</b>"),
                "不缓存也要照常渲染");
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isEmpty());
        assertTrue(pageCacheService.html(title).isEmpty());
        pageCacheService.put(title, "<p>关闭时的写入</p>", page.getLatestRevisionId());
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isEmpty(), "关闭时 put 是空操作");

        // 打开后立即生效（不用重启）：同样的调用开始读写缓存
        configure("true", "memory");
        assertTrue(pageCacheService.enabled());
        pageCacheService.body(page, content);
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent());
    }

    @Test
    void backendNoneMeansNoCachingAtAll() {
        configure("true", "none");
        String title = "缓存禁用后端页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''后端 none'''");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");

        assertFalse(pageCacheService.enabled(), "backend = none 时缓存不可用");
        assertEquals("none", pageCacheService.backendName());
        assertTrue(pageCacheService.body(page, content).html().contains("<b>后端 none</b>"));
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isEmpty());
        assertTrue(pageCacheService.html(title).isEmpty());
    }

    @Test
    void redisBackendWithoutServerFallsBackToMemory() {
        // 没有配置 spring.data.redis.* 也没有 wikikernel.cache.page.redis.enabled=true：
        // 即使把 backend 写成 redis，也只是退回进程内 LRU（功能照常，启动不受影响）
        configure("true", "redis");
        assertTrue(pageCacheService.enabled());
        assertEquals("memory", pageCacheService.backendName(), "没有 Redis 时必须退回进程内 LRU");

        String title = "缓存降级页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "降级后照样缓存");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        pageCacheService.body(page, content);
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent(),
                "退回内存后端后缓存仍然可用");
    }

    @Test
    void templateChangeInvalidatesTranscludingPagesAndPreloadsThem() {
        configure("true", "memory");
        String suffix = String.valueOf(System.nanoTime());
        String template = "模板:缓存预渲染模板" + suffix;
        String pageA = "缓存预渲染页A" + suffix;
        String pageB = "缓存预渲染页B" + suffix;
        String unrelated = "缓存预渲染无关页" + suffix;
        wikiPageService.editPage(null, template, "第一版模板正文");
        wikiPageService.editPage(null, pageA, "{{" + template + "}}");
        wikiPageService.editPage(null, pageB, "{{" + template + "}}");
        wikiPageService.editPage(null, unrelated, "没有嵌入模板");

        // 先按「页面查看」的路径把三个页面渲染进缓存
        warm(pageA);
        warm(pageB);
        warm(unrelated);
        assertTrue(cachedHtmlOf(pageA).contains("第一版模板正文"), cachedHtmlOf(pageA));

        long before = pageCacheService.preloadedPages();
        wikiPageService.editPage(null, template, "第二版模板正文");

        // 模板变更 → 嵌入它的页面立即失效；随后虚拟线程批量预渲染把它们重新写进缓存
        try {
            awaitPreloaded(before + 2, 10_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待预渲染时被中断", exception);
        }
        assertTrue(cachedHtmlOf(pageA).contains("第二版模板正文"), cachedHtmlOf(pageA));
        assertTrue(cachedHtmlOf(pageB).contains("第二版模板正文"), cachedHtmlOf(pageB));
        assertFalse(cachedHtmlOf(pageA).contains("第一版模板正文"), "模板变了就不能再给旧正文");

        // 没有嵌入该模板的页面不受影响（条目还在，内容没被重渲染）
        assertTrue(pageCacheService.get(unrelated, revisionOf(unrelated)).isPresent());
    }

    @Test
    void preloadCanBeTurnedOff() throws InterruptedException {
        configure("true", "memory");
        siteSettingsService.set(PageCacheService.PRELOAD_ON_TEMPLATE_CHANGE_KEY, "false");
        String suffix = String.valueOf(System.nanoTime());
        String template = "模板:缓存不预渲染模板" + suffix;
        String page = "缓存不预渲染页" + suffix;
        wikiPageService.editPage(null, template, "第一版");
        wikiPageService.editPage(null, page, "{{" + template + "}}");
        warm(page);
        assertTrue(cachedHtmlOf(page).contains("第一版"));

        long before = pageCacheService.preloadedPages();
        wikiPageService.editPage(null, template, "第二版");
        Thread.sleep(600L);

        assertEquals(before, pageCacheService.preloadedPages(), "关闭预渲染后不应后台渲染");
        assertTrue(pageCacheService.get(page, revisionOf(page)).isEmpty(),
                "但嵌入页面仍然被失效（下次访问时重新渲染）");
    }

    // ==================== 工具 ====================

    private void configure(String enabled, String backend) {
        siteSettingsService.set(PageCacheService.ENABLED_KEY, enabled);
        siteSettingsService.set(PageCacheService.BACKEND_KEY, backend);
    }

    /** 按「页面查看」的路径渲染一次（写进缓存），返回渲染结果。 */
    private PageBody warm(String title) {
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        return pageCacheService.body(page, content);
    }

    private String cachedHtmlOf(String title) {
        return pageCacheService.get(title, revisionOf(title))
                .map(PageBody::html)
                .orElseThrow(() -> new AssertionError("缓存里没有页面：" + title));
    }

    private Long revisionOf(String title) {
        return wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();
    }

    /** 等后台预渲染完成（异步：轮询到完成数达到 target 或超时）。 */
    private void awaitPreloaded(long target, long timeoutMillis) throws InterruptedException {
        BooleanSupplier done = () -> pageCacheService.preloadedPages() >= target;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(done.getAsBoolean(),
                "等待预渲染超时：期望至少 " + target + " 个，实际 " + pageCacheService.preloadedPages());
    }
}
