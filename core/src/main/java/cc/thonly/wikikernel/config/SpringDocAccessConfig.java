package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrlHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springdoc.webmvc.ui.SwaggerResourceResolver;
import org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在持久登录恢复会话后，按 MVC 处理器识别并保护接口文档。 */
@Configuration(proxyBeanMethods = false)
public class SpringDocAccessConfig implements WebMvcConfigurer {

    public static final String ACCESS_TARGET = "特殊:接口文档";

    private final AccessControlService accessControlService;

    public SpringDocAccessConfig(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                if (!isDocumentation(request, handler)) {
                    return true;
                }

                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                String uri = request.getRequestURI();
                boolean browserPage = handler instanceof HandlerMethod method
                        ? SwaggerWelcomeWebMvc.class.isAssignableFrom(method.getBeanType())
                        : uri.endsWith(".html");
                WikiUser user = UserSession.current(request);
                if (user != null) {
                    if (accessControlService.canRead(ACCESS_TARGET, user)) {
                        return true;
                    }
                    if (browserPage) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    } else {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    }
                } else if (browserPage) {
                    response.sendRedirect(request.getContextPath() + WikiUrlHelper.INSTANCE.loginTo(uri));
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
                return false;
            }
        }).order(Ordered.LOWEST_PRECEDENCE);
    }

    private static boolean isDocumentation(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod method) {
            return method.getBeanType().getPackageName().startsWith("org.springdoc.");
        }
        if (handler instanceof ResourceHttpRequestHandler resources) {
            if (resources.getResourceResolvers().stream().anyMatch(SwaggerResourceResolver.class::isInstance)) {
                return true;
            }
            // Swagger UI 停用后，通用静态资源映射仍可能提供原始 WebJar。
            String path = UriUtils.decode(ServletRequestPathUtils.getCachedPathValue(request), StandardCharsets.UTF_8);
            return path.startsWith("/webjars/swagger-ui/");
        }
        return false;
    }
}
