package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.config.HttpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实客户端 IP 解析：代理头只在显式信任时生效，并覆盖 Cloudflare / Docker 常见头。
 */
class ClientIpResolverTest {

    private static final String PROXY_IP = "172.18.0.5";
    private static final String CLIENT_IP = "203.0.113.9";

    @Test
    void disabledIgnoresAllForwardedHeaders() {
        ClientIpResolver resolver = resolver(false);
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", CLIENT_IP);
        request.addHeader("CF-Connection-IP", CLIENT_IP);
        request.addHeader("X-Real-IP", CLIENT_IP);
        request.addHeader("X-Forwarded-For", CLIENT_IP);

        assertEquals(PROXY_IP, resolver.resolve(request));
    }

    @Test
    void prefersCloudflareConnectingHeader() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        request.addHeader("X-Real-IP", "198.51.100.8");
        request.addHeader("CF-Connecting-IP", CLIENT_IP);

        assertEquals(CLIENT_IP, resolver.resolve(request));
    }

    @Test
    void acceptsCloudflareConnectionHeader() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connection-IP", CLIENT_IP);

        assertEquals(CLIENT_IP, resolver.resolve(request));
    }

    @Test
    void prefersXRealIpOverForwardedFor() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        request.addHeader("X-Real-IP", CLIENT_IP);

        assertEquals(CLIENT_IP, resolver.resolve(request));
    }

    @Test
    void takesFirstValidAddressFromForwardedFor() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "unknown, " + CLIENT_IP + ", 10.0.0.2");

        assertEquals(CLIENT_IP, resolver.resolve(request));
    }

    @Test
    void supportsMultipleForwardedHeaderLines() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Forwarded-For", CLIENT_IP);

        assertEquals(CLIENT_IP, resolver.resolve(request));
    }

    @Test
    void invalidHeaderFallsBackToRemoteAddress() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertEquals(PROXY_IP, resolver.resolve(request));
    }

    private static ClientIpResolver resolver(boolean trustProxyHeaders) {
        HttpProperties properties = new HttpProperties();
        properties.setTrustProxyHeaders(trustProxyHeaders);
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_IP);
        return request;
    }
}
