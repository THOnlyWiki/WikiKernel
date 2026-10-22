package cc.thonly.wikikernel.web;

import jakarta.servlet.http.Cookie;
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
