package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.BlockWriter;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * {@link BlockWriter} 的 core 实现：以某个账号的身份封禁目标，记录与日志由 {@link BlockService} 写入。
 * <p>
 * 操作者账号不存在时按「无操作者」记录（与迁移前滥用过滤器直接调用 {@code BlockService} 的行为一致），
 * 封禁本身照常生效。
 */
@Service
public class BlockWriterImpl implements BlockWriter {

    private final BlockService blockService;
    private final UserService userService;

    public BlockWriterImpl(BlockService blockService, UserService userService) {
        this.blockService = blockService;
        this.userService = userService;
    }

    @Override
    public boolean block(String actorUsername, String target, LocalDateTime expiresAt, String reason) {
        String value = Strings.trimToEmpty(target);
        if (value.isEmpty()) {
            return false;
        }
        WikiUser actor = Strings.isBlank(actorUsername)
                ? null : userService.findByUsername(actorUsername).orElse(null);
        blockService.block(actor, value, expiresAt, Strings.trimToEmpty(reason), false, false, false);
        return true;
    }
}
