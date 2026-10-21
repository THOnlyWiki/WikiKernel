package cc.thonly.wikikernel.auth.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAuthSettingsTest {

    @Test
    void returnToOnlyAllowsLocalPaths() {
        assertEquals("/wiki/首页", ExternalAuthSettings.safeReturnTo("/wiki/首页", "/"));
        assertEquals("/wiki/a?b=1", ExternalAuthSettings.safeReturnTo("/wiki/a?b=1", "/"));
        assertEquals("/", ExternalAuthSettings.safeReturnTo("//evil.example", "/"));
        assertEquals("/", ExternalAuthSettings.safeReturnTo("https://evil.example", "/"));
        assertEquals("/", ExternalAuthSettings.safeReturnTo("/\\evil", "/"));
        assertEquals("/", ExternalAuthSettings.safeReturnTo("/ok\r\nLocation: https://evil", "/"));
    }

    @Test
    void baseUrlNormalization() {
        assertEquals("https://wiki.example.com",
                ExternalAuthSettings.normalizeBaseUrl("https://wiki.example.com/"));
        assertEquals("http://127.0.0.1:18080",
                ExternalAuthSettings.normalizeBaseUrl("http://127.0.0.1:18080"));
        assertEquals("", ExternalAuthSettings.normalizeBaseUrl("javascript:alert(1)"));
        assertEquals("", ExternalAuthSettings.normalizeBaseUrl("not a url"));
    }

    @Test
    void bindingKeyIsStableAndProviderScoped() {
        String first = ExternalIdentityService.bindingKey("github", "12345");
        String second = ExternalIdentityService.bindingKey("GitHub", " 12345 ");
        assertFalse(first.isBlank());
        assertEquals(first, second);
        assertFalse(first.equals(ExternalIdentityService.bindingKey("qq", "12345")));
        assertTrue(ExternalIdentityService.bindingKey("", "12345").isBlank());
        assertTrue(ExternalIdentityService.bindingKey("github", "").isBlank());
    }
}
