package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.config.HttpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ClientInfoFilter} 除了给日志设置 RequestMetadata，还应在信任代理头时包装请求，
 * 让插件里的 {@code request.getRemoteAddr()} 也拿到真实客户端 IP。
 */
class ClientInfoFilterTest {

    private static final String PROXY_IP = "172.18.0.5";
    private static final String CLIENT_IP = "203.0.113.9";

    @Test
    void wrapsRequestWhenTrustedHeaderProvidesRealIp() throws Exception {
        ClientInfoFilter filter = filter(true);
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connection-IP", CLIENT_IP);

        AtomicReference<String> downstreamRemote = new AtomicReference<>();
        AtomicReference<String> metadataIp = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (effectiveRequest, response) -> {
            downstreamRemote.set(effectiveRequest.getRemoteAddr());
            metadataIp.set(RequestMetadata.ip());
        });

        assertEquals(CLIENT_IP, downstreamRemote.get());
        assertEquals(CLIENT_IP, metadataIp.get());
        assertNull(RequestMetadata.ip(), "请求结束后应清理 RequestMetadata");
    }

    @Test
    void keepsProxyAddressWhenTrustDisabled() throws Exception {
        ClientInfoFilter filter = filter(false);
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", CLIENT_IP);

        AtomicReference<String> downstreamRemote = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (effectiveRequest, response) -> downstreamRemote.set(effectiveRequest.getRemoteAddr()));

        assertEquals(PROXY_IP, downstreamRemote.get());
    }

    private static ClientInfoFilter filter(boolean trustProxyHeaders) {
        HttpProperties properties = new HttpProperties();
        properties.setTrustProxyHeaders(trustProxyHeaders);
        return new ClientInfoFilter(new ClientIpResolver(properties));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_IP);
        return request;
    }
}
