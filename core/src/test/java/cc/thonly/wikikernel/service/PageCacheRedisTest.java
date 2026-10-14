package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面正文缓存的 Redis 后端：本机有 Redis（{@code localhost:6379}）时才跑，
 * 没有 Redis 时整类跳过（{@code Assumptions}），不会让构建失败 ——
 * 这正是「Redis 可有可无」的验证：连不上时 {@link PageCacheService#backendName()} 会退回 {@code memory}。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.timeout=500ms",
        "wikikernel.cache.page.enabled=true",
        "wikikernel.cache.page.backend=redis",
        "wikikernel.cache.page.ttl-seconds=60"
})
class PageCacheRedisTest {

    @Autowired
    private PageCacheService pageCacheService;

    @Autowired
    private WikiPageService wikiPageService;

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue("redis".equals(pageCacheService.backendName()),
                "本机没有可用的 Redis（localhost:6379），跳过 Redis 后端用例");
    }

    @Test
    void redisBackendRoundTripInvalidateAndClear() {
        assertTrue(pageCacheService.enabled());
        String title = "缓存Redis页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''Redis 缓存正文'''");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");

        // 未命中 → 渲染 → 写进 Redis；再按标题读回（序列化 + 反序列化往返）
        PageBody rendered = pageCacheService.body(page, content);
        assertTrue(rendered.html().contains("<b>Redis 缓存正文</b>"), rendered.html());
        assertEquals(rendered.html(), pageCacheService.html(title).orElseThrow());

        // 单页失效：删掉后立即读不到
        pageCacheService.invalidate(title);
        assertTrue(pageCacheService.html(title).isEmpty());

        // 再次渲染写回 → 全量失效（版本纪元 +1）后读不到 → 之后还能继续写
        pageCacheService.body(page, content);
        assertTrue(pageCacheService.html(title).isPresent());
        pageCacheService.invalidateAll();
        assertTrue(pageCacheService.html(title).isEmpty(), "invalidateAll 之后旧纪元条目不可达");
        pageCacheService.body(page, content);
        assertTrue(pageCacheService.html(title).isPresent(), "清空之后仍然可以正常缓存");
    }

    @Test
    void redisEntriesStillGuardOnRevision() {
        String title = "缓存Redis版本页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "第一版");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        pageCacheService.body(page, content);
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent());

        WikiPage edited = wikiPageService.editPage(null, title, "第二版");
        assertTrue(pageCacheService.get(title, edited.getLatestRevisionId()).isEmpty(),
                "版本变了就不能命中旧条目");
        assertTrue(pageCacheService.html(title).isEmpty());
    }
}
