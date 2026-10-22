package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.web.WikiUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 裸根 / 自定义前缀两种模式下，接收侧改写（{@link WikiUrlRewriteFilter#rewrite(String)}）的行为：
 * 页面请求要转发到内部 {@code /wiki/…}，而**顶层保留路径**（api / 静态资源 / core 的
 * {@code /email/confirm}、{@code /password/reset…} 等路由）必须原样放行——
 * 漏一个就会让裸根部署下的确认链接 / 重置密码被当成页面标题（404）。
 */
class WikiUrlRewriteFilterTest {

    @AfterEach
    void reset() {
        WikiUrls.configure("wiki", "特殊");
    }

    @Test
    void bareRootForwardsPagePaths() {
        WikiUrls.configure("", "特殊");
        assertEquals("/wiki/首页", WikiUrlRewriteFilter.rewrite("/首页"));
        assertEquals("/wiki/用户:甲", WikiUrlRewriteFilter.rewrite("/用户:甲"));
        assertEquals("/wiki/用户:甲/子页", WikiUrlRewriteFilter.rewrite("/用户:甲/子页"));
        assertNull(WikiUrlRewriteFilter.rewrite("/"));
    }

    @Test
    void bareRootKeepsCoreRoutesAndAssets() {
        WikiUrls.configure("", "特殊");
        // core 的顶层路由（EmailConfirmController / PasswordResetController / SetupController / WikiController）
        assertNull(WikiUrlRewriteFilter.rewrite("/email/confirm"));
        assertNull(WikiUrlRewriteFilter.rewrite("/password/reset"));
        assertNull(WikiUrlRewriteFilter.rewrite("/password/reset-request"));
        assertNull(WikiUrlRewriteFilter.rewrite("/init"));
        assertNull(WikiUrlRewriteFilter.rewrite("/version"));
        assertNull(WikiUrlRewriteFilter.rewrite("/render"));
        // 原有保留段
        assertNull(WikiUrlRewriteFilter.rewrite("/api.php"));
        assertNull(WikiUrlRewriteFilter.rewrite("/api/query"));
        assertNull(WikiUrlRewriteFilter.rewrite("/css/base.css"));
        assertNull(WikiUrlRewriteFilter.rewrite("/skins/kawaii-timeless/main.css"));
        assertNull(WikiUrlRewriteFilter.rewrite("/plugins/echo/echo.js"));
        assertNull(WikiUrlRewriteFilter.rewrite("/avatars/1.png"));
        assertNull(WikiUrlRewriteFilter.rewrite("/files/abc123"));
        // 反向代理插件的固定命名空间 /proxy_<路由名>/
        assertNull(WikiUrlRewriteFilter.rewrite("/proxy_google_doc/"));
        assertNull(WikiUrlRewriteFilter.rewrite("/proxy_google_doc/document/d/abc"));
        // 带静态扩展名的请求（裸根时不渲染成「页面不存在」）——但**带命名空间冒号的页面标题**要转发
        assertNull(WikiUrlRewriteFilter.rewrite("/不存在.png"));
        assertEquals("/wiki/Gadget:ReadingProgress.css",
                WikiUrlRewriteFilter.rewrite("/Gadget:ReadingProgress.css"));
        assertEquals("/wiki/文件:X.png", WikiUrlRewriteFilter.rewrite("/文件:X.png"));
        // 浏览器把冒号发成 %3A（ListFiles 等页面链接就是这种），也要按命名空间页面转发，
        // 否则 文件:草方块.png 这类带静态扩展名的文件页会 404
        assertEquals("/wiki/%E6%96%87%E4%BB%B6%3A%E8%8D%89%E6%96%B9%E5%9D%97.png",
                WikiUrlRewriteFilter.rewrite("/%E6%96%87%E4%BB%B6%3A%E8%8D%89%E6%96%B9%E5%9D%97.png"));
        assertEquals("/wiki/%E5%B0%8F%E5%B7%A5%E5%85%B7%3AX.css",
                WikiUrlRewriteFilter.rewrite("/%E5%B0%8F%E5%B7%A5%E5%85%B7%3AX.css"));
        // %2E 编码的点也算静态扩展名（解码后再判断）
        assertNull(WikiUrlRewriteFilter.rewrite("/x%2Ecss"));
    }

    @Test
    void customPrefixRewritesPrefixPaths() {
        WikiUrls.configure("p", "特殊");
        assertEquals("/wiki/首页", WikiUrlRewriteFilter.rewrite("/p/首页"));
        assertEquals("/wiki/特殊:搜索", WikiUrlRewriteFilter.rewrite("/p/特殊:搜索"));
        assertEquals("/", WikiUrlRewriteFilter.rewrite("/p"));
        assertEquals("/", WikiUrlRewriteFilter.rewrite("/p/"));
        // 非前缀路径（含 /wiki/ 旧路径）不动
        assertNull(WikiUrlRewriteFilter.rewrite("/wiki/首页"));
        assertNull(WikiUrlRewriteFilter.rewrite("/api/query"));
    }

    @Test
    void defaultPrefixNeverRewrites() {
        WikiUrls.configure("wiki", "特殊");
        assertNull(WikiUrlRewriteFilter.rewrite("/wiki/首页"));
        assertNull(WikiUrlRewriteFilter.rewrite("/首页"));
    }
}