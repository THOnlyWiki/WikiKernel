package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.CrawlerPolicy;
import cc.thonly.wikikernel.plugin.PluginManager;
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
 * 执行插件注册的 {@link CrawlerPolicy}：例如 ai-crawler-block 默认拦截 AI 爬虫 UA，
 * 同时让 Googlebot / Bingbot 等搜索引擎通过。
 * <p>
 * core 不硬编码厂商名单：没有插件时没有策略，所有请求原样放行。
 * {@code /robots.txt}（以及常见拼写 {@code /robot.txt} / {@code /Robot.txt}）本身始终放行，
 * 让爬虫能读到规则。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CrawlerPolicyFilter extends OncePerRequestFilter {

    private final PluginManager pluginManager;

    public CrawlerPolicyFilter(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path != null && ("/robots.txt".equals(path) || "/robot.txt".equals(path)
                || "/Robot.txt".equals(path) || "/Robots.txt".equals(path)
                || "/favicon.ico".equals(path))) {
            chain.doFilter(request, response);
            return;
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        for (CrawlerPolicy policy : pluginManager.extensions(CrawlerPolicy.class)) {
            boolean blocked;
            try {
                blocked = policy.blocks(userAgent);
            } catch (RuntimeException exception) {
                // 单个策略异常不能影响正常访问。
                blocked = false;
            }
            if (blocked) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("X-Robots-Tag", "noindex, nofollow");
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Access denied by site crawler policy.\n");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
