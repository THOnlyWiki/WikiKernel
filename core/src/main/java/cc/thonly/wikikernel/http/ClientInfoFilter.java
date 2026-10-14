package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.common.util.Strings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把每个请求的客户端 IP / User-Agent 放进 {@link RequestMetadata}（日志记录用），请求结束后清除。
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
        RequestMetadata.set(clientIpResolver.resolve(request), userAgent);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestMetadata.clear();
        }
    }
}
