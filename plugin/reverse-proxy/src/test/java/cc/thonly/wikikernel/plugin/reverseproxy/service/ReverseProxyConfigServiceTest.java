package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseProxyConfigServiceTest {

    @Test
    void savesNormalizesAndReloadsRoutes() {
        MemorySiteConfig site = new MemorySiteConfig();
        ReverseProxyConfigService service = new ReverseProxyConfigService(site, new ObjectMapper());
        service.saveRoutes(List.of(new ReverseProxyRoute(
                "google_doc", "https://docs.google.com/", "", true,
                false, false, "get, head", 0, "", true)));

        ReverseProxyRoute route = service.find("google_doc").orElseThrow();
        assertEquals("https://docs.google.com", route.getUpstream());
        assertEquals("admin", route.getAccess());
        assertEquals("GET,HEAD", route.getMethods());
        assertEquals(30, route.getTimeoutSeconds());
        assertTrue(route.isAllowPrivateTargets());
        assertFalse(route.isRewriteHtml());
        assertEquals("google_doc", route.getLabel());
        assertTrue(site.values.containsKey(ReverseProxyConfigService.ROUTES_KEY));

        ReverseProxyConfigService reloaded = new ReverseProxyConfigService(site, new ObjectMapper());
        assertEquals(1, reloaded.routes().size());
        assertEquals("google_doc", reloaded.routes().get(0).getSlug());
    }

    @Test
    void acceptsLegacyJsonArray() {
        MemorySiteConfig site = new MemorySiteConfig();
        site.values.put(ReverseProxyConfigService.ROUTES_KEY,
                "[{\"slug\":\"legacy\",\"upstream\":\"https://example.com\","
                        + "\"access\":\"public\",\"enabled\":true,\"methods\":\"GET\","
                        + "\"timeoutSeconds\":30,\"label\":\"Legacy\"}]");
        ReverseProxyConfigService service = new ReverseProxyConfigService(site, new ObjectMapper());
        assertEquals(1, service.routes().size());
        assertEquals("legacy", service.routes().get(0).getSlug());
    }

    @Test
    void rejectsDuplicateAndInvalidTargets() {
        MemorySiteConfig site = new MemorySiteConfig();
        ReverseProxyConfigService service = new ReverseProxyConfigService(site, new ObjectMapper());
        ReverseProxyRoute route = new ReverseProxyRoute(
                "a", "https://example.com", "public", true,
                false, true, "GET", 30, "A", false);
        service.saveRoutes(List.of(route));
        assertThrows(IllegalArgumentException.class, () -> service.saveRoutes(List.of(route, route)));

        ReverseProxyRoute badScheme = new ReverseProxyRoute(
                "b", "ftp://example.com", "public", true,
                false, true, "GET", 30, "B", false);
        assertThrows(IllegalArgumentException.class, () -> service.saveRoutes(List.of(badScheme)));

        ReverseProxyRoute badSlug = new ReverseProxyRoute(
                "bad/slug", "https://example.com", "public", true,
                false, true, "GET", 30, "Bad", false);
        assertThrows(IllegalArgumentException.class, () -> service.saveRoutes(List.of(badSlug)));
    }

    @Test
    void updatesByOriginalSlugAndDeletes() {
        MemorySiteConfig site = new MemorySiteConfig();
        ReverseProxyConfigService service = new ReverseProxyConfigService(site, new ObjectMapper());
        service.saveRoutes(List.of(new ReverseProxyRoute(
                "old", "https://example.com", "public", true,
                false, true, "GET", 30, "Old", false)));

        ReverseProxyRoute renamed = new ReverseProxyRoute(
                "new", "https://example.org", "login", true,
                true, true, "GET,HEAD", 45, "New", false);
        service.upsert(renamed, "old");
        assertTrue(service.find("old").isEmpty());
        assertEquals("new", service.find("new").orElseThrow().getSlug());
        assertTrue(service.delete("new"));
        assertTrue(service.routes().isEmpty());
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
            if (value == null) {
                return defaultValue;
            }
            return "true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }
    }
}
