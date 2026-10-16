package cc.thonly.wikikernel.common.service;

/**
 * 邮件发送桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 按 core 的 {@code wikikernel.email.*} 配置（SMTP 服务器 / 总开关 / 每种类型开关）发送纯文本邮件。
 * <p>
 * 与 core 的 {@code MailService} 相比：这里**不抛异常**——未配置 / 总开关关闭 / 类型被禁用 / 发送失败
 * 都只返回 {@code false}（失败原因记在 core 日志），插件拿它做「有机会就发一封」的通知类邮件即可。
 * <p>
 * 注意：SMTP 发送可能耗时（超时上限 15 秒）——**不要在数据库事务里同步调用**，
 * 也不要让它拖慢用户的保存请求（建议放后台线程 / afterCommit）。
 */
public interface Mailer {

    /** SMTP 配置是否完整（至少要有服务器与发件人地址）。 */
    boolean isConfigured();

    /** 总开关 + 类型开关；类型未在配置里指定时用类型默认值（见 {@code common.extension.EmailType}）。 */
    boolean isEnabled(String typeId);

    /**
     * 尝试发送一封纯文本邮件：未配置 / 被禁用 / 收件人为空 / 发送失败都返回 {@code false}，不抛异常。
     *
     * @param typeId  邮件类型 id（与 {@code EmailType.id()} / {@code wikikernel.email.types.<id>} 对应）
     * @param to      收件人地址
     * @param subject 主题
     * @param text    正文（纯文本）
     * @return 是否发送成功（投递到 SMTP 服务器；不代表对方收到）
     */
    boolean trySend(String typeId, String to, String subject, String text);
}