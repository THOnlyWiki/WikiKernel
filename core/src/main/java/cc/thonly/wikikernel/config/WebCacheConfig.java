package cc.thonly.wikikernel.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 缓存策略（解决“跳页时 logo/样式短暂刷新”）：
 * <ul>
 *     <li><b>静态资源</b>（{@code /css/ /js/ /img/}）：交给 Spring 的资源缓存
 *     （{@code spring.web.resources.cache.cachecontrol.max-age}，见 application.yaml），
 *     浏览器本地命中，跳页不再重新下载；</li>
 *     <li><b>动态页面 / API</b>：{@code Cache-Control: no-cache}（可缓存但每次回源校验），
 *     避免编辑后看到旧页面。</li>
 * </ul>
 * Spring Security 默认给所有响应加 {@code no-store}，会禁用一切缓存，已在 SecurityConfig 关闭。
 */
@Configuration(proxyBeanMethods = false)
public class WebCacheConfig implements WebMvcConfigurer {

    /** 静态资源路径前缀。 */
    private static final String[] STATIC_PREFIXES = {"/css/", "/js/", "/img/", "/vendor/"};

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                for (String prefix : STATIC_PREFIXES) {
                    if (path.startsWith(prefix)) {
                        return true; // 静态资源走 Spring 的长缓存头
                    }
                }
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
                return true;
            }
        });
    }
}
