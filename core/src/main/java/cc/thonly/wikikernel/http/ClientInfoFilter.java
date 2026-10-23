package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.common.util.Strings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把每个请求的客户端 IP / User-Agent 放进 {@link RequestMetadata}（日志记录用），请求结束后清除。
 * <p>
 * 开启 {@code wikikernel.http.trust-proxy-headers} 后，还会包装请求，让下游（包括插件里直接调用
 * {@code request.getRemoteAddr()} 的代码）也拿到真实客户端 IP，而不是 Docker / Nginx 的代理地址。
 */
@Component
public class ClientInfoFilter extends OncePerRequestFilter {

    /** User-Agent 入库前截断长度（与 wiki_log.user_agent 列一致）。 */
    private static final int USER_AGENT_MAX = 500;

    private final ClientIpResolver clientIpResolver;

    public ClientInfoFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userAgent = Strings.trimToEmpty(request.getHeader("User-Agent"));
        if (userAgent.length() > USER_AGENT_MAX) {
            userAgent = userAgent.substring(0, USER_AGENT_MAX);
        }
        String clientIp = clientIpResolver.resolve(request);
        RequestMetadata.set(clientIp, userAgent);

        HttpServletRequest effectiveRequest = request;
        String remoteAddr = Strings.trimToEmpty(request.getRemoteAddr());
        if (!clientIp.equals(remoteAddr)) {
            effectiveRequest = new ClientIpRequestWrapper(request, clientIp);
        }
        try {
            filterChain.doFilter(effectiveRequest, response);
        } finally {
            RequestMetadata.clear();
        }
    }

    /** 只覆盖 {@code getRemoteAddr()}，避免影响请求的其它行为。 */
    private static final class ClientIpRequestWrapper extends HttpServletRequestWrapper {

        private final String clientIp;

        private ClientIpRequestWrapper(HttpServletRequest request, String clientIp) {
            super(request);
            this.clientIp = clientIp;
        }

        @Override
        public String getRemoteAddr() {
            return clientIp;
        }
    }
}
