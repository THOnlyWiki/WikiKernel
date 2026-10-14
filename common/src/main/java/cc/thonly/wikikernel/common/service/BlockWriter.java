package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;

/**
 * 封禁写入桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 插件（如滥用过滤器的 {@code block} 操作）需要以某个账号的身份封禁触发者时用它，
 * 封禁记录与通用日志由 core 的 {@code BlockService} 负责写入。
 * 只读的封禁判断（当前用户是否被封禁）用 {@link AccessCheck#blockNotice}.
 */
public interface BlockWriter {

    /**
     * 以某个账号的身份封禁 / 更新封禁（同一目标已有生效封禁时更新它）。
     *
     * @param actorUsername 操作者账号名（通常是插件创建的系统账号；不存在时返回 false）
     * @param target        用户名 / IP / IPv4 CIDR
     * @param expiresAt     到期时间（{@code null} = 永久）
     * @param reason        原因（已本地化的文案）
     * @return 是否写入成功
     */
    boolean block(String actorUsername, String target, LocalDateTime expiresAt, String reason);
}
