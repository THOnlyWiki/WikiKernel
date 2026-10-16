package cc.thonly.wikikernel.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 同源校验（CSRF 防护）：对 {@code /api/**} 的**写请求**（POST / PUT / PATCH / DELETE）
 * 检查 {@code Origin}（缺失时退回 {@code Referer}）：
 * <ul>
 *     <li>来源与请求的 Host 同源 → 放行（本站前端、同源脚本都正常）；</li>
 *     <li>浏览器发出且带**外部来源** → 403（跨站表单 / 跨站 fetch 无法通过）；</li>
 *     <li>完全没有 Origin / Referer（curl、服务端脚本等非浏览器调用）→ 放行，
 *         这类请求本来就带不了受害者的 Cookie，不构成 CSRF。</li>
 * </ul>
 * 之前 {@code /api/**} 的写接口只靠登录态判断，跨站表单带 Cookie 就能触发（例如删首页、
 * 改权限规则）；这里补上同源检查。{@code /api.php}（Action API）不在本过滤器范围内，
 * 它自己用 MediaWiki 风格的 {@code token} 参数校验。
 */
@Component
public class SameOriginWriteFilter extends OncePerRequestFilter {

    /** 需要检查的写方法。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (sameOrigin(source, request.getHeader("Host"))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"跨站请求已被拒绝\",\"success\":false}");
    }

    /** {@code Origin}/{@code Referer} 的主机（含端口）是否与请求 Host 一致。 */
    private static boolean sameOrigin(String source, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(source);
            String sourceHost = uri.getHost();
            if (sourceHost == null) {
                return false;
            }
            String sourcePort = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : String.valueOf(uri.getPort());
            int colon = host.lastIndexOf(':');
            String hostName = colon > 0 ? host.substring(0, colon) : host;
            String hostPort = colon > 0 ? host.substring(colon + 1) : null;
            if (hostPort == null) {
                hostPort = defaultPort(uri.getScheme());
            }
            return sourceHost.equalsIgnoreCase(hostName) && sourcePort.equals(hostPort);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? "443" : "80";
    }
}
