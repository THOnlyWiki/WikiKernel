package cc.thonly.wikikernel.common.service;

/**
 * 登录会话建立桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 用途：**替代登录方式**的插件（指纹登录、OAuth、邮箱链接登录……）在「验证通过」后，
 * 以某个用户的身份建立与 core 密码登录**完全一致**的会话状态：
 * 往当前请求的 {@code HttpSession} 里放 core 约定的 {@code wikiUser} 属性
 * （{@code WikiUser.toTransferCopy()}，密码已脱敏），并按「记住我」设置会话有效期
 * （30 天 / 30 分钟，与 core 的 {@code UserSession.login} 相同）。
 * <p>
 * 这是平台能力（会话约定属于 core，插件不该自己抄一份属性名 / 有效期）；插件只能建立
 * **已存在用户**的会话（用户不存在时抛 {@link IllegalArgumentException}）。
 * 「记住我」的持久登录 Cookie / 令牌属于 core 的密码登录流程，这里不做。
 */
public interface LoginSession {

    /**
     * 以某个用户的身份建立当前请求的会话。
     *
     * @param userId   用户 id
     * @param remember 是否延长会话有效期（等价于密码登录表单里的「记住我的登录状态」）
     * @throws IllegalArgumentException 用户不存在 / 无请求上下文
     */
    void establish(long userId, boolean remember);

    /** 退出登录（使当前会话失效，与 core 的 {@code UserSession.logout} 相同）。 */
    void logout();
}
