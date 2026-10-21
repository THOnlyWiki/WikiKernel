package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.web.WikiUrls;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 裸根部署（{@code wikikernel.urls.page-prefix: ""}）时，把旧的 {@code /wiki/标题}
 * 301 到规范的 {@code /标题}。这样生产切换后旧外链仍然可用，但地址栏不会再停留在
 * {@code /wiki/}；默认 {@code /wiki/} 部署下这个过滤器不生效。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LegacyWikiPathRedirectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!WikiUrls.bareRoot()) {
            chain.doFilter(request, response);
            return;
        }
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String path = context.isEmpty() || uri == null ? uri : uri.substring(context.length());
        if (!"/wiki".equals(path) && (path == null || !path.startsWith("/wiki/"))) {
            chain.doFilter(request, response);
            return;
        }
        String rest = "/wiki".equals(path) ? "" : path.substring("/wiki/".length());
        String location = (context.isEmpty() ? "" : context) + "/" + rest;
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            location += "?" + query;
        }
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", location);
    }
}
