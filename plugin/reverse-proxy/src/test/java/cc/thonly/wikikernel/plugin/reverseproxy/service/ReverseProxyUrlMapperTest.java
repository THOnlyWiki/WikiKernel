package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReverseProxyUrlMapperTest {

    private static final ReverseProxyRoute ROUTE = new ReverseProxyRoute(
            "google_doc", "https://docs.google.com", "admin", true,
            true, true, "GET,HEAD", 30, "Google Docs", false);

    @Test
    void mapsRootUpstreamAndQuery() {
        URI target = ReverseProxyUrlMapper.target(ROUTE, "/document/d/abc", "x=1");
        assertEquals("https://docs.google.com/document/d/abc?x=1", target.toString());
        assertEquals("/proxy_google_doc/", ReverseProxyUrlMapper.proxyPath(
                "google_doc", URI.create(ROUTE.getUpstream()), URI.create("https://docs.google.com/")));
        assertEquals("/proxy_google_doc/document/d/abc?x=1", ReverseProxyUrlMapper.proxyPath(
                "google_doc", URI.create(ROUTE.getUpstream()), target));
    }

    @Test
    void mapsUpstreamBasePath() {
        ReverseProxyRoute route = new ReverseProxyRoute(
                "docs", "https://example.com/docs", "public", true,
                false, true, "GET,HEAD", 30, "Docs", false);
        URI target = ReverseProxyUrlMapper.target(route, "/guide/start", null);
        assertEquals("https://example.com/docs/guide/start", target.toString());
        assertEquals("/proxy_docs/guide/start", ReverseProxyUrlMapper.proxyPath(
                "docs", URI.create(route.getUpstream()), target));
        assertEquals("/proxy_docs/", ReverseProxyUrlMapper.proxyPath(
                "docs", URI.create(route.getUpstream()), URI.create("https://example.com/docs")));
    }

    @Test
    void rewritesRelativeUrlsAndLeavesExternalUrlsAlone() {
        URI upstream = URI.create("https://example.com/docs");
        URI page = URI.create("https://example.com/docs/page.html");
        assertEquals("/proxy_docs/img/logo.png",
                ReverseProxyUrlMapper.rewriteUrl("docs", upstream, page, "img/logo.png"));
        assertEquals("/proxy_docs/next?x=1#part",
                ReverseProxyUrlMapper.rewriteUrl("docs", upstream, page, "/docs/next?x=1#part"));
        assertEquals("https://cdn.example.org/a.js",
                ReverseProxyUrlMapper.rewriteUrl("docs", upstream, page, "https://cdn.example.org/a.js"));
        assertEquals("#local",
                ReverseProxyUrlMapper.rewriteUrl("docs", upstream, page, "#local"));
    }

    @Test
    void rejectsTraversalAndEncodedSlashes() {
        assertThrows(IllegalArgumentException.class,
                () -> ReverseProxyUrlMapper.target(ROUTE, "/../secret", null));
        assertThrows(IllegalArgumentException.class,
                () -> ReverseProxyUrlMapper.target(ROUTE, "/%2e%2e/secret", null));
        assertThrows(IllegalArgumentException.class,
                () -> ReverseProxyUrlMapper.target(ROUTE, "/a%2fb", null));
        assertNull(ReverseProxyUrlMapper.suffix("/other/path", "google_doc"));
    }
}
