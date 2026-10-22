package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置（LocalSettings.yaml 的 {@code wikikernel.auth}）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.auth")
public class AuthProperties {

    /**
     * 持久登录令牌有效期（天）：0 或负数 = 永不过期（不推荐）。
     * <p>
     * 默认 30 天；令牌存数据库，服务重启不丢；浏览器 Cookie 的有效期也跟着这个值走。
     * 改密会撤销该用户的全部持久令牌。
     */
    private int tokenExpireDays = 30;

    /** 持久登录 Cookie 名。 */
    private String tokenCookieName = "wiki_token";
}
