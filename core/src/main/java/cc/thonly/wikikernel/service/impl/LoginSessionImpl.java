package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.LoginSession;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link LoginSession} 的 core 实现：让替代登录方式的插件（指纹登录等）在「验证通过」后
 * 建立与 core 密码登录**完全相同**的会话状态。
 * <p>
 * 会话内容与 {@code ApiController#login} 的成功分支一致：
 * <pre>
 * UserSession.login(request, wikiUser.toTransferCopy(), remember);
 * </pre>
 * 即 Session 属性 {@value #SESSION_ATTRIBUTE} = 密码脱敏后的 {@link WikiUser}，
 * 有效期 30 天（记住我）/ 30 分钟（否则）。之后 core 的 {@code CurrentUser} / 模板 / 权限判断
 * 读到的就是同一个用户，和密码登录没有区别。
 * <p>
 * 两处与 core 的约定保持一致（core 侧常量在 {@code UserSession}）：
 * <ul>
 *     <li>会话属性名 {@value #SESSION_ATTRIBUTE}；</li>
 *     <li>会话有效期 30 天 / 30 分钟。</li>
 * </ul>
 * 用户读取走 core 自己的 {@link WikiUserMapper}（插件不直接摸 {@code wiki_user} 表）；
 * 「记住我」的持久登录 Cookie（{@code wiki_login_token}）不在本桥范围内：那是 core
 * 密码登录流程里额外签发的东西，{@code UserSession.login} 本身并不涉及。
 */
@Service
public class LoginSessionImpl implements LoginSession {

    /** 会话属性名（与 core 的 {@code UserSession.ATTRIBUTE} 相同）。 */
    public static final String SESSION_ATTRIBUTE = "wikiUser";

    private final WikiUserMapper userMapper;

    public LoginSessionImpl(WikiUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void establish(long userId, boolean remember) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            throw new IllegalArgumentException("no request context");
        }
        WikiUser user = userId <= 0 || userMapper == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found: " + userId);
        }
        // 复用 core 的登录入口：属性 / 有效期 / Session ID 轮换等行为完全一致。
        UserSession.login(request, user.toTransferCopy(), remember);
    }

    @Override
    public void logout() {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            UserSession.logout(request);
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
