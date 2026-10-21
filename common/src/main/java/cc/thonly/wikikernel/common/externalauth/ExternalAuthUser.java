package cc.thonly.wikikernel.common.externalauth;

/**
 * provider 返回的稳定身份。
 *
 * @param subject       provider 侧不可变用户 id（GitHub 数字 id / QQ openid）
 * @param username      provider 侧用户名（GitHub login / QQ 昵称）
 * @param displayName   展示名
 * @param email         已验证邮箱；provider 不提供时为空
 * @param emailVerified 邮箱是否由 provider 验证
 * @param avatarUrl     头像 URL，可空
 */
public record ExternalAuthUser(String subject, String username, String displayName,
                               String email, boolean emailVerified, String avatarUrl) {

    public ExternalAuthUser {
        subject = subject == null ? "" : subject.trim();
        username = username == null ? "" : username.trim();
        displayName = displayName == null ? "" : displayName.trim();
        email = email == null ? "" : email.trim();
        avatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
    }
}
