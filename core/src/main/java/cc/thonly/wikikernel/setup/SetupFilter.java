package cc.thonly.wikikernel.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安装模式拦截：需要安装时（{@code wikikernel.setup.mode=auto} 且 LocalSettings.yaml 不存在），
 * 除安装页面 / 接口与静态资源外，所有请求都跳到 {@code /init}（API 返回 409）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SetupFilter extends OncePerRequestFilter {

    private final SetupService setupService;
    private final MessageSource messageSource;

    public SetupFilter(SetupService setupService, MessageSource messageSource) {
        this.setupService = setupService;
        this.messageSource = messageSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!setupService.isRequired()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (allowed(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json;charset=UTF-8");
            String message = messageSource.getMessage("setup.required", null, "setup required",
                    LocaleContextHolder.getLocale());
            response.getWriter().write("{\"code\":409,\"message\":\"" + escapeJson(message) + "\"}");
            return;
        }
        response.sendRedirect(request.getContextPath() + "/init");
    }

    /** 安装模式下允许访问的路径。 */
    private static boolean allowed(String path) {
        return path.equals("/init")
                || path.startsWith("/api/setup")
                || path.equals("/api/version")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/skins/")
                || path.startsWith("/vendor/")
                || path.startsWith("/img/")
                // 配置项的 JSON Schema：/init「下载完整配置」的模板首行指向它（IDE 补全用），
                // 属于随应用一起发布的静态资源（与 /css、/js 同级），不含任何本机配置
                || path.startsWith("/schemas/")
                || path.equals("/favicon.ico")
                || path.equals("/error");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
