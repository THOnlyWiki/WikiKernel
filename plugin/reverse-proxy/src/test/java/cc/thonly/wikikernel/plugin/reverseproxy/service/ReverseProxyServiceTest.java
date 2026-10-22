package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseProxyServiceTest {

    @Test
    void streamsUpstreamResponseAndDropsWikiCookie() throws Exception {
        AtomicReference<String> upstreamCookie = new AtomicReference<>();
        AtomicReference<String> upstreamAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> {
            upstreamCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            upstreamAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            MemorySiteConfig site = new MemorySiteConfig();
            ReverseProxyConfigService config = new ReverseProxyConfigService(site,
                    new tools.jackson.databind.ObjectMapper());
            config.saveRoutes(List.of(new ReverseProxyRoute(
                    "demo", "http://127.0.0.1:" + port, "public", true,
                    false, true, "GET,HEAD", 30, "Demo", true)));
            ReverseProxyService service = new ReverseProxyService(config,
                    new AllowAllAccess(), () -> Viewer.ANONYMOUS, new ReverseProxyHtmlRewriter());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy_demo/hello");
            request.setRequestURI("/proxy_demo/hello");
            request.addHeader("Cookie", "wiki=top-secret");
            request.addHeader("Authorization", "Bearer top-secret");
            MockHttpServletResponse response = new MockHttpServletResponse();

            service.proxy("demo", request, response);

            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("hello"));
            assertFalse("wiki=top-secret".equals(upstreamCookie.get()));
            assertFalse("Bearer top-secret".equals(upstreamAuthorization.get()));
            assertEquals("SAMEORIGIN", response.getHeader("X-Frame-Options"));
            assertTrue(String.valueOf(response.getHeader("Content-Security-Policy")).contains("sandbox"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksLoopbackTargetsByDefault() throws Exception {
        MemorySiteConfig site = new MemorySiteConfig();
        ReverseProxyConfigService config = new ReverseProxyConfigService(site,
                new tools.jackson.databind.ObjectMapper());
        config.saveRoutes(List.of(new ReverseProxyRoute(
                "local", "http://127.0.0.1:19090", "public", true,
                false, true, "GET,HEAD", 30, "Local", false)));
        ReverseProxyService service = new ReverseProxyService(config,
                new AllowAllAccess(), () -> Viewer.ANONYMOUS, new ReverseProxyHtmlRewriter());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy_local/");
        request.setRequestURI("/proxy_local/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ReverseProxyException exception = assertThrows(ReverseProxyException.class,
                () -> service.proxy("local", request, response));
        assertEquals(403, exception.status());
    }

    private static final class MemorySiteConfig implements SiteConfig {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Boolean.parseBoolean(value);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }
    }

    private static final class AllowAllAccess implements AccessCheck {
        @Override
        public boolean can(AccessAction action, String target, Viewer viewer) {
            return true;
        }

        @Override
        public Optional<String> blockNotice(Viewer viewer) {
            return Optional.empty();
        }
    }
}
