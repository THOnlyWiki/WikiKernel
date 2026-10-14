package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「配了 Redis 但机器上没装 / 连不上」的降级路径：
 * 应用照常启动（{@code @SpringBootTest} 上下文能起来就说明启动了）、
 * 缓存自动退回进程内 LRU、页面渲染与缓存功能不受影响。
 * <p>
 * 故意指向 6399 端口（没有 Redis）；如果该端口真的有人在跑 Redis，用例自动跳过。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6399",
        "spring.data.redis.timeout=300ms",
        "wikikernel.cache.page.enabled=true",
        "wikikernel.cache.page.backend=redis"
})
class PageCacheRedisDownTest {

    /** 故意指向一个没有 Redis 的端口。 */
    private static final int DEAD_PORT = 6399;

    @Autowired
    private PageCacheService pageCacheService;

    @Autowired
    private WikiPageService wikiPageService;

    @BeforeEach
    void requireDeadPort() {
        Assumptions.assumeFalse(portOpen(DEAD_PORT),
                "127.0.0.1:" + DEAD_PORT + " 上真的有 Redis，跳过降级用例");
    }

    @Test
    void configuredButUnreachableRedisFallsBackToMemory() {
        assertTrue(pageCacheService.enabled(), "Redis 连不上时缓存仍应可用（退回进程内 LRU）");
        assertEquals("memory", pageCacheService.backendName(),
                "连不上 Redis 必须退回进程内 LRU，而不是让页面渲染失败");

        String title = "Redis降级页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''Redis 不可用时照样渲染'''");
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        PageBody body = pageCacheService.body(page, content);
        assertTrue(body.html().contains("<b>Redis 不可用时照样渲染</b>"), body.html());
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent(),
                "退回内存后端之后缓存照常工作");
        assertTrue(pageCacheService.html(title).isPresent());
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
