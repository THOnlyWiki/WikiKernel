package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;
import cc.thonly.wikikernel.config.MailProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件配置逻辑：总开关、类型开关、未配置时的错误（不真正连 SMTP）。 */
class MailServiceTest {

    private static EmailTypeRegistry registry(String... ids) {
        List<EmailType> types = java.util.Arrays.stream(ids)
                .map(id -> (EmailType) new BuiltInEmailType(id, 10))
                .toList();
        return new EmailTypeRegistry() {
            @Override
            public List<EmailType> all() {
                return types;
            }

            @Override
            public Optional<EmailType> byId(String id) {
                return types.stream().filter(type -> type.id().equals(id)).findFirst();
            }

            @Override
            public String displayNameOf(String id) {
                return id;
            }
        };
    }

    private static MailProperties properties(boolean enabled, String host) {
        MailProperties properties = new MailProperties();
        properties.setEnabled(enabled);
        properties.setHost(host);
        properties.setUsername("wiki@example.com");
        properties.setFrom("wiki@example.com");
        return properties;
    }

    @Test
    void masterSwitchDisablesEverything() {
        MailServiceImpl service = new MailServiceImpl(
                properties(false, "smtp.example.com"), registry("confirm-email"));
        assertTrue(service.isConfigured());
        assertFalse(service.isEnabled("confirm-email"));
        MailException exception = assertThrows(MailException.class,
                () -> service.send("confirm-email", "a@example.com", "s", "b"));
        assertTrue(exception.messageKey().equals("email.disabled"));
    }

    @Test
    void perTypeSwitchOverridesDefault() {
        MailProperties properties = properties(true, "smtp.example.com");
        properties.getTypes().put("confirm-email", false);
        MailServiceImpl service = new MailServiceImpl(properties, registry("confirm-email", "password-reset"));

        assertFalse(service.isEnabled("confirm-email"));
        assertTrue(service.isEnabled("password-reset"), "未单独配置的类型用默认值（启用）");
        assertFalse(service.isEnabled("unknown-type"), "未注册的类型默认不启用");
    }

    @Test
    void missingHostIsNotConfigured() {
        MailServiceImpl service = new MailServiceImpl(
                properties(true, ""), registry("confirm-email"));
        assertFalse(service.isConfigured());
        MailException exception = assertThrows(MailException.class,
                () -> service.send("confirm-email", "a@example.com", "s", "b"));
        assertTrue(exception.messageKey().equals("email.notConfigured"));
    }

    @Test
    void missingRecipientRejected() {
        MailServiceImpl service = new MailServiceImpl(
                properties(true, "smtp.example.com"), registry("confirm-email"));
        MailException exception = assertThrows(MailException.class,
                () -> service.send("confirm-email", "", "s", "b"));
        assertTrue(exception.messageKey().equals("email.noAddress"));
    }
}
