package cc.thonly.wikikernel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 开发期安全配置：放行所有请求，方便直接调试插件接口。
 * <p>
 * 接入正式登录 / 权限体系时请删除本类或收紧规则。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        // /api/** 供前端 fetch 调用（登录/注册），先关闭 CSRF；接入正式权限体系时再收紧
        // /api.php 为 MediaWiki 兼容 Action API：自行校验会话 token（meta=tokens），同样不做 Spring CSRF
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/api.php"));
        // 关掉 Spring Security 默认的 no-store（否则 logo/CSS/JS 每次跳页都要重新下载、会闪）；
        // 具体缓存策略见 WebCacheConfig + spring.web.resources.cache
        http.headers(headers -> headers.cacheControl(cache -> cache.disable()));
        return http.build();
    }
}
