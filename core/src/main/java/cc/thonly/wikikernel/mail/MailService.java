package cc.thonly.wikikernel.mail;

/**
 * 邮件发送服务：按 {@code wikikernel.email.*} 配置通过 SMTP 发送。
 * <p>
 * 未配置 / 总开关关闭 / 类型被禁用时抛 {@link MailException}（消息键可本地化）。
 */
public interface MailService {

    /** SMTP 配置是否完整（至少要有服务器与发件人地址）。 */
    boolean isConfigured();

    /** 总开关 + 类型开关；类型未在配置里指定时用类型默认值。 */
    boolean isEnabled(String typeId);

    /** 发送一封纯文本邮件；失败抛 {@link MailException}。 */
    void send(String typeId, String to, String subject, String text);
}
