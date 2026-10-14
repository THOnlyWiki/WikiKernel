package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件发送配置（LocalSettings.yaml 的 {@code wikikernel.email.*}）。
 * <p>
 * 适配多种常见 SMTP：587 + STARTTLS（默认）、465 + SSL、内网无验证 SMTP；
 * 每种邮件类型（如 {@code confirm-email} / {@code password-reset} / {@code user-to-user}）
 * 可以在 {@link #types} 下单独开关，未配置时用类型自身的默认值。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.email")
public class MailProperties {

    /** 总开关（false = 所有邮件都不发送）。 */
    private boolean enabled = false;

    /** SMTP 服务器地址，如 {@code smtp.qq.com}。 */
    private String host = "";

    /** SMTP 端口（587 STARTTLS / 465 SSL / 25 明文）。 */
    private int port = 587;

    /** SMTP 登录用户名（QQ 邮箱就是邮箱地址）。 */
    private String username = "";

    /** SMTP 登录密码 / 授权码。 */
    private String password = "";

    /** 是否需要身份验证。 */
    private boolean auth = true;

    /** 启用 STARTTLS（587 常用）。 */
    private boolean starttls = true;

    /** 启用 SSL（465 常用；开启后 STARTTLS 忽略）。 */
    private boolean ssl = false;

    /** 发件人地址（留空 = 用 {@link #username}）。 */
    private String from = "";

    /** 发件人显示名。 */
    private String fromName = "WikiKernel";

    /** 确认 / 重置等令牌的有效期（小时）。 */
    private int tokenExpireHours = 24;

    /** 邮箱确认邮件的发送冷却（秒；0 = 不限制），防止被用来反复刷别人的邮箱。 */
    private int confirmCooldownSeconds = 60;

    /** 密码重置邮件的发送冷却（秒；0 = 不限制），同一账号短时间内只发一封。 */
    private int passwordResetCooldownSeconds = 300;

    /** 站点基础地址（用于邮件里的链接，如 {@code https://wiki.example.com}；留空 = 按请求自动推导）。 */
    private String baseUrl = "";

    /** 每种邮件类型的开关（id → 是否启用）；未配置时用类型默认值。 */
    private Map<String, Boolean> types = new LinkedHashMap<>();
}
