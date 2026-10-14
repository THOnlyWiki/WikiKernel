package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封禁服务（仿 MediaWiki 的 block）：
 * 目标可以是用户名或 IP / IPv4 CIDR；支持永久 / 定时、禁止发邮件、禁止创建账号、仅封禁匿名用户；
 * 封禁与解封都写日志（{@code block} / {@code unblock}）。
 */
public interface BlockService {

    /** 当前用户（按用户名）或当前请求 IP（含 CIDR，尊重「仅匿名」）命中的生效封禁。 */
    Optional<WikiBlock> currentBlock(WikiUser user);

    /** 当前用户是否被禁止发送电子邮件（仅用户封禁的 preventEmail 生效）。 */
    boolean emailBlocked(WikiUser user);

    /** 当前请求 IP 是否被禁止创建账号（IP 封禁的 preventCreate 生效；注册时用）。 */
    boolean createBlocked();

    /** 封禁提示文案（本地化：原因 + 到期）。 */
    String notice(WikiBlock block);

    /**
     * 封禁 / 更新封禁：同一目标已有生效封禁时更新它，否则新建。
     *
     * @param expiresAt null = 永久
     */
    WikiBlock block(WikiUser actor, String target, LocalDateTime expiresAt, String reason,
                    boolean preventEmail, boolean preventCreate, boolean anonOnly);

    /** 解封：记录解封者与时间并写日志；已解封 / 不存在返回 false。 */
    boolean unblock(WikiUser actor, long blockId);

    /** 生效中的封禁（永久或未到期、未解封），新的在前。 */
    List<WikiBlock> activeBlocks();

    /** 按目标查生效中的封禁（用户名忽略大小写；IP 精确）。 */
    Optional<WikiBlock> findActiveByTarget(String target);

    /** 按 ID 查封禁记录。 */
    Optional<WikiBlock> findById(long blockId);
}
