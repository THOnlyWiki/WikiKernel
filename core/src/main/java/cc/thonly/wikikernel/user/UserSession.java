package cc.thonly.wikikernel.user;

import cc.thonly.wikikernel.common.data.WikiUser;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 登录状态：存 HTTP Session（key 为 {@link #ATTRIBUTE}）。
 * <p>
 * Session 里只放 {@link WikiUser#toTransferCopy()}（密码已脱敏），不放哈希本身。
 */
public final class UserSession {

    /** Session 属性名。 */
    public static final String ATTRIBUTE = "wikiUser";

    /** 勾选“记住我的登录状态”时的会话有效期：30 天。 */
    private static final int REMEMBER_SECONDS = 30 * 24 * 60 * 60;

    /** 未勾选时的会话有效期：30 分钟（与 Tomcat 默认一致）。 */
    private static final int DEFAULT_SECONDS = 30 * 60;

    private UserSession() {
    }

    /** 登录 / 注册 / 外部登录成功时建立会话；已有 Session 会先轮换 ID，防止 Session 固定攻击。 */
    public static void login(HttpServletRequest request, WikiUser user, boolean remember) {
        // 登录 / 注册 / 外部登录成功时轮换 Session ID，防止预登录 Session 固定攻击。
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            try {
                request.changeSessionId();
            } catch (IllegalStateException | UnsupportedOperationException ignored) {
                // 某些测试容器 / 极旧 Servlet 容器不支持；保留原 ID，但不阻断登录。
            }
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(ATTRIBUTE, user);
        session.setMaxInactiveInterval(remember ? REMEMBER_SECONDS : DEFAULT_SECONDS);
    }

    /** 当前登录用户；未登录返回 {@code null}。 */
    @Nullable
    public static WikiUser current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(ATTRIBUTE);
        return value instanceof WikiUser user ? user : null;
    }

    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 用最新用户信息刷新已有会话（改皮肤偏好等场景），不改变会话有效期。 */
    public static void refresh(HttpServletRequest request, WikiUser user) {
        HttpSession session = request.getSession(false);
        if (session != null && user != null) {
            session.setAttribute(ATTRIBUTE, user);
        }
    }
}
