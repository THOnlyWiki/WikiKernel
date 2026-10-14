package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 时区接入：每个请求开始时按登录用户的时区偏好设置 {@link WikiTime} 的显示时区，
 * 请求结束后清理。这样模板里的 {@code ${@wikiTime.format(时间)}} 与
 * 特殊页 Java 侧的 {@code WikiTime.convert(...)} 自动跟随用户偏好，无需各处传时区。
 */
@Configuration(proxyBeanMethods = false)
public class WikiTimeConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                WikiTime.setZone(WikiTime.zoneOf(UserSession.current(request)));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception exception) {
                WikiTime.clearZone();
            }
        });
    }
}
