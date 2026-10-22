package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.Optional;

/**
 * 持久登录令牌（「记住登录状态」）：独立于内存 Session，存数据库，服务重启后仍可自动登录。
 * <p>
 * Cookie 值格式 {@code selector:validator}；数据库只存 selector 与 validator 的 SHA-256，
 * 令牌泄露面小。默认有效期 30 天（{@code wikikernel.auth.token-expire-days}），
 * 改密时撤销该用户的全部令牌。
 */
public interface LoginTokenService {

    /** 为用户签发新令牌，返回写入 Cookie 的值（{@code selector:validator}）。 */
    String issue(long userId);

    /** 校验 Cookie 值；成功返回最新用户（并刷新最近使用时间）。 */
    Optional<WikiUser> resolve(String cookieValue);

    /** 撤销令牌（Cookie 退出登录时）；不存在时静默。 */
    void revoke(String cookieValue);

    /** 撤销某用户的全部持久令牌（改密 / 管理员强制下线时使用）。 */
    void revokeAll(long userId);
}
