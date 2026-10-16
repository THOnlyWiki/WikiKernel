package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.service.Mailer;
import cc.thonly.wikikernel.common.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link Mailer} 桥的 core 实现：转调 {@link MailService}，把异常收成返回值。
 * <p>
 * 给插件用：未配置 / 总开关关闭 / 类型禁用 / 发送失败都只返回 {@code false}（失败记 WARN 日志），
 * 插件不需要（也拿不到）core 的 {@link MailException}。
 */
@Service
public class MailerImpl implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(MailerImpl.class);

    private final MailService mailService;

    public MailerImpl(MailService mailService) {
        this.mailService = mailService;
    }

    @Override
    public boolean isConfigured() {
        return mailService.isConfigured();
    }

    @Override
    public boolean isEnabled(String typeId) {
        return mailService.isEnabled(typeId);
    }

    @Override
    public boolean trySend(String typeId, String to, String subject, String text) {
        if (Strings.isBlank(to)) {
            return false;
        }
        try {
            mailService.send(typeId, to, subject, text);
            return true;
        } catch (MailException exception) {
            // 未配置 / 被禁用属正常（插件按「有机会就发」调用）：配置类问题只记 debug，真正的发送失败记 warn
            String key = Strings.trimToEmpty(exception.getMessage());
            if (key.startsWith("email.disabled") || key.startsWith("email.notConfigured")) {
                log.debug("[mailer] 跳过邮件（{}）：{}", typeId, key);
            } else {
                log.warn("[mailer] 邮件发送失败（{}）：{}", typeId, key);
            }
            return false;
        } catch (RuntimeException exception) {
            log.warn("[mailer] 邮件发送异常（{}）：{}", typeId, exception.toString());
            return false;
        }
    }
}