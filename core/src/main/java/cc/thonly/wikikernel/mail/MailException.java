package cc.thonly.wikikernel.mail;

/**
 * 邮件发送相关异常：{@link #messageKey()} 是消息键（如 {@code email.notConfigured}），
 * {@link #args()} 是可选的消息参数（如冷却剩余秒数），调用方用 MessageSource 翻译后展示。
 */
public class MailException extends RuntimeException {

    private final String messageKey;
    private final Object[] args;

    public MailException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args;
    }

    public String messageKey() {
        return messageKey;
    }

    /** 消息参数（用于 {@code {0}} 之类的占位符），永不为 null。 */
    public Object[] args() {
        return args;
    }
}
