package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiBlockMapper;
import cc.thonly.wikikernel.http.IpAddresses;
import cc.thonly.wikikernel.http.RequestMetadata;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.time.WikiTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封禁实现：生效判定 = 未解封且（永久或未到期）。
 * 用户名按大小写不敏感匹配；IP 支持精确与 IPv4 CIDR（{@link IpAddresses}）。
 */
@Service
public class BlockServiceImpl implements BlockService {

    private final WikiBlockMapper blockMapper;
    private final LogService logService;
    private final MessageSource messageSource;

    public BlockServiceImpl(WikiBlockMapper blockMapper, LogService logService, MessageSource messageSource) {
        this.blockMapper = blockMapper;
        this.logService = logService;
        this.messageSource = messageSource;
    }

    @Override
    public Optional<WikiBlock> currentBlock(WikiUser user) {
        LocalDateTime now = LocalDateTime.now();
        List<WikiBlock> active = activeBlocks(now);
        if (user != null) {
            Optional<WikiBlock> byName = active.stream()
                    .filter(block -> "user".equals(block.getTargetType()))
                    .filter(block -> block.getTarget().equalsIgnoreCase(Strings.trimToEmpty(user.getUsername())))
                    .findFirst();
            if (byName.isPresent()) {
                return byName;
            }
        }
        String ip = RequestMetadata.ip();
        if (Strings.isBlank(ip)) {
            return Optional.empty();
        }
        return active.stream()
                .filter(block -> "ip".equals(block.getTargetType()))
                .filter(block -> !(Boolean.TRUE.equals(block.getAnonOnly()) && user != null))
                .filter(block -> IpAddresses.matches(ip, block.getTarget()))
                .findFirst();
    }

    @Override
    public boolean emailBlocked(WikiUser user) {
        if (user == null) {
            return false;
        }
        return activeBlocks(LocalDateTime.now()).stream()
                .filter(block -> "user".equals(block.getTargetType()))
                .filter(block -> block.getTarget().equalsIgnoreCase(Strings.trimToEmpty(user.getUsername())))
                .anyMatch(block -> Boolean.TRUE.equals(block.getPreventEmail()));
    }

    @Override
    public boolean createBlocked() {
        String ip = RequestMetadata.ip();
        if (Strings.isBlank(ip)) {
            return false;
        }
        return activeBlocks(LocalDateTime.now()).stream()
                .filter(block -> "ip".equals(block.getTargetType()))
                .filter(block -> Boolean.TRUE.equals(block.getPreventCreate()))
                .anyMatch(block -> IpAddresses.matches(ip, block.getTarget()));
    }

    @Override
    public String notice(WikiBlock block) {
        String reason = Strings.isBlank(block.getReason()) ? message("block.noReason") : block.getReason();
        String expiry = block.getExpiresAt() == null
                ? message("block.permanent") : WikiTime.format(block.getExpiresAt(), WikiTime.DEFAULT_DATE_TIME_PATTERN);
        return message("block.notice", reason, expiry);
    }

    @Override
    public WikiBlock block(WikiUser actor, String target, LocalDateTime expiresAt, String reason,
                           boolean preventEmail, boolean preventCreate, boolean anonOnly) {
        String value = Strings.trimToEmpty(target);
        LocalDateTime now = LocalDateTime.now();
        WikiBlock block = findActiveByTarget(value).orElse(null);
        boolean created = block == null;
        if (created) {
            block = new WikiBlock();
            block.setCreateAt(now);
        }
        block.setTarget(value);
        block.setTargetType(IpAddresses.isIpOrCidr(value) ? "ip" : "user");
        block.setByUserId(actor == null ? null : actor.getId());
        block.setReason(Strings.trimToEmpty(reason));
        block.setExpiresAt(expiresAt);
        block.setPreventEmail(preventEmail);
        block.setPreventCreate(preventCreate);
        block.setAnonOnly(anonOnly && "ip".equals(block.getTargetType()));
        if (created) {
            blockMapper.insert(block);
        } else {
            blockMapper.updateById(block);
        }
        logService.record(LogTypeConfig.BLOCK, actor == null ? null : actor.getId(), value, null,
                Strings.trimToEmpty(reason));
        return block;
    }

    @Override
    public boolean unblock(WikiUser actor, long blockId) {
        WikiBlock block = blockMapper.selectById(blockId);
        if (block == null || block.getUnblockedAt() != null) {
            return false;
        }
        block.setUnblockedBy(actor == null ? null : actor.getId());
        block.setUnblockedAt(LocalDateTime.now());
        blockMapper.updateById(block);
        logService.record(LogTypeConfig.UNBLOCK, actor == null ? null : actor.getId(), block.getTarget(), null,
                Strings.trimToEmpty(block.getReason()));
        return true;
    }

    @Override
    public List<WikiBlock> activeBlocks() {
        return activeBlocks(LocalDateTime.now());
    }

    @Override
    public Optional<WikiBlock> findActiveByTarget(String target) {
        String value = Strings.trimToEmpty(target);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return activeBlocks(LocalDateTime.now()).stream()
                .filter(block -> block.getTarget().equalsIgnoreCase(value))
                .findFirst();
    }

    @Override
    public Optional<WikiBlock> findById(long blockId) {
        return Optional.ofNullable(blockMapper.selectById(blockId));
    }

    /** 生效中的封禁（未解封且未到期），新的在前。 */
    private List<WikiBlock> activeBlocks(LocalDateTime now) {
        return blockMapper.selectList(Wrappers.<WikiBlock>lambdaQuery()
                .isNull(WikiBlock::getUnblockedAt)
                .and(wrapper -> wrapper.isNull(WikiBlock::getExpiresAt)
                        .or().gt(WikiBlock::getExpiresAt, now))
                .orderByDesc(WikiBlock::getCreateAt)
                .orderByDesc(WikiBlock::getId));
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
