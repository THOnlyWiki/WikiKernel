package cc.thonly.wikikernel.mail;

/**
 * 忘记密码（密码重置，仿 MediaWiki 的 {@code Special:PasswordReset}）：
 * 按用户名 / 电子邮箱找到账号后，发送一封带一次性重置链接的邮件。
 * <p>
 * 对外**不暴露账号是否存在**（无论发送与否，页面提示一致）；重置链接：
 * {@code /password/reset?token=…}，有效期与邮箱确认共用 {@code wikikernel.email.token-expire-hours}。
 */
public interface PasswordResetService {

    /** 重置结果。 */
    enum Result {
        /** 成功设置新密码。 */
        OK,
        /** 令牌无效 / 过期 / 类型不符 / 邮箱已变更。 */
        INVALID
    }

    /**
     * 请求重置：找到账号（绑定了非空邮箱）时签发令牌并发邮件；
     * 找不到 / 没邮箱 / 在发送冷却内 / 邮件未配置时静默跳过（不抛异常、不泄露账号信息）。
     */
    void request(String usernameOrEmail, String baseUrl);

    /** 令牌当前是否可用于重置（类型正确、未过期）。 */
    boolean valid(String rawToken);

    /** 用令牌设置新密码（一次性；邮箱变更后旧链接失效）。 */
    Result reset(String rawToken, String newPassword);
}
