package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内置邮件类型（注册进 {@link EmailTypeRegistry}）：
 * 邮箱确认 / 密码重置 / 用户间邮件。
 * 插件可通过 {@code PluginContext.register(EmailType.class, ...)} 追加自己的类型。
 * <p>
 * 每种类型可在 {@code wikikernel.email.types.<id>} 单独开 / 关。
 */
@Configuration(proxyBeanMethods = false)
public class EmailTypeConfig {

    /** 邮箱确认（确认后自动获得「邮箱确认用户」权限）。 */
    public static final String CONFIRM_EMAIL = "confirm-email";

    /** 重置密码邮件。 */
    public static final String PASSWORD_RESET = "password-reset";

    /** 用户间电子邮件。 */
    public static final String USER_TO_USER = "user-to-user";

    @Bean
    public EmailType confirmEmailType() {
        return new BuiltInEmailType(CONFIRM_EMAIL, 10);
    }

    @Bean
    public EmailType passwordResetEmailType() {
        return new BuiltInEmailType(PASSWORD_RESET, 20);
    }

    @Bean
    public EmailType userToUserEmailType() {
        return new BuiltInEmailType(USER_TO_USER, 30);
    }
}
