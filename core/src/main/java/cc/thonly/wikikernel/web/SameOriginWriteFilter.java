package cc.thonly.wikikernel.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
 *     <li>{@code Origin: null} → 403（沙盒 / 隐藏来源不能证明同源）；</li>
 *     <li>完全没有 Origin / Referer：没有登录 Cookie 时放行（curl、服务端脚本等）；
 *         带登录 Cookie 时必须带同源 JS 的 {@code X-Requested-With: XMLHttpRequest}，
 *         否则拒绝，避免“无来源 + 浏览器仍带 Cookie”的 CSRF。</li>
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
        String origin = trim(request.getHeader("Origin"));
        if (!origin.isEmpty()) {
            // Origin: null 不是“没有来源可以信任”的意思：它表示来源被隐藏
            // （沙盒 iframe / 某些 WebView），不能证明同源，一律拒绝。
            if ("null".equalsIgnoreCase(origin)) {
                reject(response);
                return;
            }
            if (sameOrigin(origin, request.getHeader("Host"))) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response);
            return;
        }

        String referer = trim(request.getHeader("Referer"));
        if (!referer.isEmpty()) {
            if (sameOrigin(referer, request.getHeader("Host"))) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response);
            return;
        }

        // 没有 Origin / Referer：非浏览器客户端通常不带登录 Cookie，可以放行；
        // 若带着会话 Cookie（或 URL 重写里的 ;jsessionid），无法证明同源，拒绝。
        // 同源 fetch 如果浏览器没有发送来源头，可带自定义 X-Requested-With（跨站表单无法伪造）。
        if (hasAuthenticationCookie(request) || hasUrlSessionId(request)) {
            if (isXmlHttpRequest(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isXmlHttpRequest(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"跨站请求已被拒绝\",\"success\":false}");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasUrlSessionId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.toLowerCase(Locale.ROOT).contains(";jsessionid=");
    }

    /** 是否存在可能携带登录态的 Cookie（Tomcat Session / 持久登录令牌）。 */
    private static boolean hasAuthenticationCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if ("JSESSIONID".equalsIgnoreCase(name) || "wiki_token".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
