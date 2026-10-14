package cc.thonly.wikikernel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 跨域（LocalSettings.yaml 的 {@code wikikernel.cors}）：
 * 启用后注册一个**最高优先级**的 {@link CorsFilter}——在 Spring Security 过滤器链之前
 * 处理预检（OPTIONS）与跨域响应头；未启用 / 未配置来源时不注册，行为与原来完全一致。
 * <p>
 * 配置见 {@link CorsProperties}；优先级：命令行参数 / 环境变量 &gt; LocalSettings.yaml &gt; 默认值。
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** 过滤器名（日志 / 排查用）。 */
    static final String FILTER_NAME = "wikiCorsFilter";

    @Bean
    public FilterRegistrationBean<CorsFilter> wikiCorsFilter(CorsProperties properties) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>();
        registration.setName(FILTER_NAME);
        if (!isActive(properties)) {
            registration.setEnabled(false);
            // 注意：即使禁用也要设置一个 filter——Boot 启动时仍会读取注册信息（getDescription），
            // filter 为 null 会导致 Tomcat 启动失败（'filter' must not be null）。
            registration.setFilter(new CorsFilter(new UrlBasedCorsConfigurationSource()));
            log.info("CORS 未启用（wikikernel.cors 需要 enabled=true 且 allowed-origins / "
                    + "allowed-origin-patterns 至少配一个来源）");
            return registration;
        }
        CorsConfiguration configuration = configurationOf(properties);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> paths = clean(properties.getPaths());
        if (paths.isEmpty()) {
            source.registerCorsConfiguration("/**", configuration);
        } else {
            for (String path : paths) {
                source.registerCorsConfiguration(path, configuration);
            }
        }
        registration.setFilter(new CorsFilter(source));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        log.info("CORS 已启用：origins={}，origin-patterns={}，credentials={}，paths={}",
                configuration.getAllowedOrigins(), configuration.getAllowedOriginPatterns(),
                configuration.getAllowCredentials(), paths.isEmpty() ? List.of("/**") : paths);
        return registration;
    }

    /** 是否生效：开关打开且至少配置了一个来源（精确或模式）。 */
    static boolean isActive(CorsProperties properties) {
        return properties.isEnabled()
                && (!clean(properties.getAllowedOrigins()).isEmpty()
                || !clean(properties.getAllowedOriginPatterns()).isEmpty());
    }

    /**
     * 由配置构建 Spring 的 {@link CorsConfiguration}（静态方法方便单测）：
     * 去空白；{@code allow-credentials=true} 时把精确来源里的 {@code "*"} 转成来源模式
     * （浏览器规范不允许 credentials + 精确 "*"，模式会按请求 Origin 回显）。
     */
    static CorsConfiguration configurationOf(CorsProperties properties) {
        List<String> origins = clean(properties.getAllowedOrigins());
        List<String> patterns = new ArrayList<>(clean(properties.getAllowedOriginPatterns()));
        if (properties.isAllowCredentials() && origins.contains(CorsConfiguration.ALL)) {
            origins = origins.stream().filter(origin -> !CorsConfiguration.ALL.equals(origin)).toList();
            if (!patterns.contains(CorsConfiguration.ALL)) {
                patterns.add(CorsConfiguration.ALL);
            }
            log.warn("CORS：allow-credentials=true 时不能用 \"*\" 作为精确来源，"
                    + "已改为按来源模式回显请求 Origin");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        if (!origins.isEmpty()) {
            configuration.setAllowedOrigins(origins);
        }
        if (!patterns.isEmpty()) {
            configuration.setAllowedOriginPatterns(patterns);
        }
        List<String> methods = clean(properties.getAllowedMethods());
        if (!methods.isEmpty()) {
            configuration.setAllowedMethods(methods);
        }
        List<String> headers = clean(properties.getAllowedHeaders());
        if (!headers.isEmpty()) {
            configuration.setAllowedHeaders(headers);
        }
        List<String> exposed = clean(properties.getExposedHeaders());
        if (!exposed.isEmpty()) {
            configuration.setExposedHeaders(exposed);
        }
        configuration.setAllowCredentials(properties.isAllowCredentials());
        configuration.setMaxAge(properties.getMaxAge());
        return configuration;
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .toList();
    }
}
