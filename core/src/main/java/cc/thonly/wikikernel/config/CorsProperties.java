package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 跨域配置（LocalSettings.yaml 的 {@code wikikernel.cors}）。
 * <p>
 * 默认**关闭**（不添加任何跨域响应头）；开启后按
 * {@link #allowedOrigins}（精确匹配，{@code "*"} = 任意来源）与
 * {@link #allowedOriginPatterns}（通配模式，如 {@code https://*.example.com}）放行。
 * <pre>
 * wikikernel:
 *   cors:
 *     enabled: true
 *     allowed-origins:
 *       - https://example.com
 *     allow-credentials: true
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.cors")
public class CorsProperties {

    /** 总开关；false（默认）= 不注册跨域过滤器（同源访问行为不变）。 */
    private boolean enabled = false;

    /** 允许的来源（精确匹配，如 {@code https://example.com}；{@code "*"} = 任意来源）。 */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 允许的来源模式（如 {@code https://*.example.com}；与 allowedOrigins 至少配一个才生效）。 */
    private List<String> allowedOriginPatterns = new ArrayList<>();

    /** 允许的请求方法（预检 Access-Control-Allow-Methods）。 */
    private List<String> allowedMethods = new ArrayList<>(
            List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

    /** 允许的请求头（{@code "*"} = 任意；预检 Access-Control-Allow-Headers）。 */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

    /** 允许前端 JS 读取的响应头（Access-Control-Expose-Headers）。 */
    private List<String> exposedHeaders = new ArrayList<>();

    /**
     * 是否允许携带凭据（Cookie / Authorization / TLS 客户端证书）。
     * <p>
     * 浏览器规范：{@code allow-credentials: true} 时不能用 {@code "*"} 作精确来源；
     * 本配置会自动把 {@code "*"} 转成来源模式（按请求 Origin 回显），并记一条日志。
     */
    private boolean allowCredentials = false;

    /** 预检（OPTIONS）结果缓存秒数；0 = 不缓存。 */
    private long maxAge = 3600;

    /** 生效路径（Ant 风格；默认空 = 全部路径），如 {@code /api/**}。 */
    private List<String> paths = new ArrayList<>();
}
