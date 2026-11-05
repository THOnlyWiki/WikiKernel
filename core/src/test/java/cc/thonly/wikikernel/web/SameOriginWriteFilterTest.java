package cc.thonly.wikikernel.web;

import jakarta.servlet.http.Cookie;
import cc.thonly.wikikernel.config.HttpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SameOriginWriteFilterTest {

    private final SameOriginWriteFilter filter = new SameOriginWriteFilter();

    @Test
    void rejectsNullAndMissingOriginWhenSessionCookieIsPresent() throws Exception {
        assertEquals(403, status("null", true));
        assertEquals(403, status("", true));
        assertEquals(403, status("null", false));
        assertEquals(200, status("", false));
    }

    @Test
    void checksOriginAndRefererAgainstHost() throws Exception {
        assertEquals(200, status("http://wiki.test", true));
        assertEquals(200, status("", true, "http://wiki.test/path"));
        assertEquals(403, status("https://evil.example", true));
        assertEquals(403, status("", true, "https://evil.example/path"));
    }

    @Test
    void comparesSchemesPortsAndIpv6Hosts() throws Exception {
        assertEquals(403, originStatus("https", "wiki.test", "http://wiki.test"));
        assertEquals(403, originStatus("http", "wiki.test", "https://wiki.test"));
        assertEquals(200, originStatus("https", "wiki.test", "https://wiki.test:443"));
        assertEquals(403, originStatus("https", "wiki.test:8443", "https://wiki.test"));
        assertEquals(200, originStatus("https", "wiki.test:8443", "https://wiki.test:8443"));
        assertEquals(200, originStatus("http", "[::1]:8080", "http://[::1]:8080"));
        assertEquals(403, originStatus("http", "wiki.test", "http://evil@wiki.test"));
        assertEquals(403, originStatus("http", "wiki.test", "ftp://wiki.test"));
    }

    @Test
    void forwardedSchemeRequiresExplicitProxyTrust() throws Exception {
        for (boolean trusted : new boolean[]{false, true}) {
            HttpProperties properties = new HttpProperties();
            properties.setTrustProxyHeaders(trusted);
            SameOriginWriteFilter proxyFilter = new SameOriginWriteFilter(properties);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/watch");
            request.setScheme("http");
            request.addHeader("Host", "wiki.test");
            request.addHeader("Origin", "https://wiki.test");
            request.addHeader("X-Forwarded-Proto", "https");
            MockHttpServletResponse response = new MockHttpServletResponse();
            proxyFilter.doFilter(request, response, (req, res) -> {});
            assertEquals(trusted ? 200 : 403, response.getStatus());
        }
    }

    private int originStatus(String scheme, String host, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/watch");
        request.setScheme(scheme);
        request.addHeader("Host", host);
        request.addHeader("Origin", origin);
        request.setCookies(new Cookie("JSESSIONID", "test-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response.getStatus();
    }

    private int status(String origin, boolean sessionCookie) throws Exception {
        return status(origin, sessionCookie, null);
    }

    private int status(String origin, boolean sessionCookie, String referer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/watch");
        request.setServerName("wiki.test");
        request.setServerPort(80);
        request.addHeader("Host", "wiki.test");
        if (!origin.isEmpty()) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        if (sessionCookie) {
            request.setCookies(new Cookie("JSESSIONID", "test-session"));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        return response.getStatus();
    }
}
