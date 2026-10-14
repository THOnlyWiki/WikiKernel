package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.data.WikiEmailToken;

import java.util.Optional;

/**
 * 邮件令牌服务：邮箱确认 / 密码重置等一次性链接的签发与校验。
 * <p>
 * 明文只出现在邮件链接里，数据库存 SHA-256 摘要；重新签发会先撤销旧的同类型令牌。
 */
public interface EmailTokenService {

    /** 签发新令牌（同用户同类型旧的先撤销），返回明文令牌。 */
    String issue(long userId, String type, String email, int expireHours);

    /** 解析令牌（无效 / 过期返回空；过期令牌会被清掉；不消费）。 */
    Optional<WikiEmailToken> resolve(String rawToken);

    /** 消费（删除）令牌。 */
    void consume(WikiEmailToken token);

    /** 撤销某用户某类型的全部令牌。 */
    void revokeAll(long userId, String type);

    /** 某用户某类型最近一次签发时间（发送冷却判断用；从未签发过为空）。 */
    java.util.Optional<java.time.LocalDateTime> lastIssuedAt(long userId, String type);
}
