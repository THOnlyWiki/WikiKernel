package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP / 反向代理相关配置（LocalSettings.yaml 的 {@code wikikernel.http.*}）。
 * <p>
 * 部署在 Docker 反向代理 / Cloudflare 等 CDN 后面时，把 {@link #trustProxyHeaders} 设为 true，
 * 日志记录与用户核查会用真实客户端 IP（依次读取 CF-Connecting-IP / CF-Connection-IP /
 * True-Client-IP / X-Real-IP / X-Forwarded-For）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.http")
public class HttpProperties {

    /** 是否信任代理 / CDN 的转发头；false（默认）= 直接使用连接来源地址（RemoteAddr）。 */
    private boolean trustProxyHeaders = false;
}
