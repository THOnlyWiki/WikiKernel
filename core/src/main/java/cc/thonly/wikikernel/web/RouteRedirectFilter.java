package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.service.SiteConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 可配置的路径重定向（站点设置 {@code route.redirects} / LocalSettings.yaml 的
 * {@code wikikernel.route.redirects}）：
 * <pre>
 * /excel=/excel/
 * /线下活动速查表=/wiki/线下活动速查表
 * /old=/new, 301
 * </pre>
 * 每行一条 {@code 源路径=目标}；目标可再跟 {@code , 301} 指定用 301（默认 302 临时重定向）。
 * 源路径支持前缀通配 {@code /old/*}（匹配 {@code /old/} 及其下所有路径）。
 * <p>
 * 规则从站点设置**实时读取**（改完立刻生效，无需重启）；空行与 {@code #} 注释行忽略。
 * 过滤器在 {@code WikiUrlRewriteFilter} 之前执行，命中即短路，不再走页面路由。
 * <p>
 * 安全：只接受站内目标（以 {@code /} 开头且不是 {@code //host}），外站目标一律忽略，
 * 避免被配置成开放重定向。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RouteRedirectFilter extends OncePerRequestFilter {

    /** 站点设置里的键（与 config-catalog.yaml 一致）。 */
    public static final String CONFIG_KEY = "route.redirects";

    private final SiteConfig siteConfig;

    public RouteRedirectFilter(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        Map<String, Rule> rules = rules();
        Rule matched = rules.get(path);
        if (matched == null) {
            // 前缀通配（源路径以 /* 结尾）
            for (Map.Entry<String, Rule> entry : rules.entrySet()) {
                if (entry.getKey().endsWith("/*")) {
                    String prefix = entry.getKey().substring(0, entry.getKey().length() - 1);
                    if (path.startsWith(prefix)) {
                        matched = new Rule(entry.getValue().target + path.substring(prefix.length()),
                                entry.getValue().permanent);
                        break;
                    }
                }
            }
        }
        if (matched == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String query = request.getQueryString();
        String location = matched.target + (query == null || query.isEmpty() ? "" : "?" + query);
        response.setStatus(matched.permanent ? HttpServletResponse.SC_MOVED_PERMANENTLY : HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
    }

    /** 解析配置（每次请求读取，保证站点设置改完即时生效）。 */
    Map<String, Rule> rules() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        String raw = siteConfig.get(CONFIG_KEY, "");
        if (raw == null || raw.isBlank()) {
            return rules;
        }
        for (String line : raw.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int equals = text.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String source = text.substring(0, equals).trim();
            String target = text.substring(equals + 1).trim();
            boolean permanent = false;
            int comma = target.lastIndexOf(',');
            if (comma > 0) {
                String flag = target.substring(comma + 1).trim();
                if ("301".equals(flag) || "permanent".equalsIgnoreCase(flag)) {
                    permanent = true;
                    target = target.substring(0, comma).trim();
                }
            }
            if (source.isEmpty() || !source.startsWith("/") || source.startsWith("//")) {
                continue;
            }
            // 只允许站内目标：外站（含协议 / //host）一律忽略，防止开放重定向
            if (target.isEmpty() || !target.startsWith("/") || target.startsWith("//")) {
                continue;
            }
            rules.put(source, new Rule(target, permanent));
        }
        return rules;
    }

    /** 一条重定向规则。 */
    record Rule(String target, boolean permanent) {
    }

    @Override
    protected String getFilterName() {
        return "route-redirect-filter";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        // 静态资源、API 与皮肤/插件资源不做重定向（避免把后台请求也拐走）
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("/api/") || lower.startsWith("/api.php") || lower.startsWith("/rest.php")
                || lower.startsWith("/files/") || lower.startsWith("/css/") || lower.startsWith("/js/")
                || lower.startsWith("/vendor/") || lower.startsWith("/skins/") || lower.startsWith("/plugins/")
                || lower.startsWith("/img/") || lower.startsWith("/avatars/");
    }
}
