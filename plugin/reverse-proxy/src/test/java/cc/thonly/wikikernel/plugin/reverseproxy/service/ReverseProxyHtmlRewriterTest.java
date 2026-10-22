package cc.thonly.wikikernel.plugin.reverseproxy.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseProxyHtmlRewriterTest {

    private final ReverseProxyHtmlRewriter rewriter = new ReverseProxyHtmlRewriter();

    @Test
    void rewritesSameOriginHtmlAndCssUrls() {
        String html = """
                <!doctype html><html><head>
                <link rel="stylesheet" href="assets/site.css">
                <style>.hero{background:url('img/hero.png')}</style>
                </head><body>
                <a href="/docs/next">next</a>
                <img src="img/logo.png" srcset="img/logo.png 1x, img/logo@2x.png 2x">
                <a href="https://other.example/a">external</a>
                <script src="app.js"></script>
                </body></html>
                """;
        byte[] out = rewriter.rewriteHtml("docs",
                URI.create("https://example.com/docs"),
                URI.create("https://example.com/docs/page.html"),
                html.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8");
        String result = new String(out, StandardCharsets.UTF_8);
        assertTrue(result.contains("/proxy_docs/assets/site.css"), result);
        assertTrue(result.contains("/proxy_docs/img/hero.png"), result);
        assertTrue(result.contains("/proxy_docs/next"), result);
        assertTrue(result.contains("/proxy_docs/img/logo.png"), result);
        assertTrue(result.contains("/proxy_docs/app.js"), result);
        assertTrue(result.contains("https://other.example/a"), result);
    }

    @Test
    void rewritesCssUrls() {
        byte[] out = rewriter.rewriteCss("docs",
                URI.create("https://example.com/docs"),
                URI.create("https://example.com/docs/style.css"),
                "body{background:url('img/bg.png')}".getBytes(StandardCharsets.UTF_8),
                "text/css; charset=utf-8");
        assertTrue(new String(out, StandardCharsets.UTF_8).contains("/proxy_docs/img/bg.png"));
    }

    @Test
    void leavesUnrewritableContentAlone() {
        byte[] body = "<html><body>plain</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] out = rewriter.rewriteHtml("docs",
                URI.create("https://example.com"),
                URI.create("https://example.com/page"),
                body, "image/png");
        assertFalse(new String(out, StandardCharsets.UTF_8).isEmpty());
    }
}
