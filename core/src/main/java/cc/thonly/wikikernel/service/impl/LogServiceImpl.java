package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiLogMapper;
import cc.thonly.wikikernel.http.IpAddresses;
import cc.thonly.wikikernel.http.RequestMetadata;
import cc.thonly.wikikernel.service.LogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LogServiceImpl implements LogService {

    /** 按 IP / CIDR 查询时，最多扫描多少条最近日志（内存里做 CIDR 匹配）。 */
    private static final int IP_SCAN_LIMIT = 5000;

    private final WikiLogMapper wikiLogMapper;

    public LogServiceImpl(WikiLogMapper wikiLogMapper) {
        this.wikiLogMapper = wikiLogMapper;
    }

    @Override
    public void record(String logType, Long actorUserId, String target, Long targetUserId, String detail) {
        WikiLog log = new WikiLog();
        log.setLogType(logType);
        log.setActorUserId(actorUserId);
        log.setTarget(Strings.trimToEmpty(target));
        log.setTargetUserId(targetUserId);
        log.setDetail(detail == null ? "" : detail);
        log.setHided(false);
        // 每一条日志都带客户端 IP / UA（CDN 场景取真实地址，见 wikikernel.http）
        log.setIp(RequestMetadata.ip());
        log.setUserAgent(RequestMetadata.userAgent());
        log.setTime(LocalDateTime.now());
        wikiLogMapper.insert(log);
    }

    @Override
    public List<WikiLog> search(String logType, Long relatedUserId, String target,
                                LocalDateTime start, LocalDateTime end, boolean includeHided,
                                int limit, int offset) {
        LambdaQueryWrapper<WikiLog> query = Wrappers.lambdaQuery();
        if (Strings.isNotBlank(logType)) {
            query.eq(WikiLog::getLogType, logType);
        }
        if (relatedUserId != null) {
            query.and(wrapper -> wrapper
                    .eq(WikiLog::getActorUserId, relatedUserId)
                    .or()
                    .eq(WikiLog::getTargetUserId, relatedUserId));
        }
        if (Strings.isNotBlank(target)) {
            query.like(WikiLog::getTarget, target);
        }
        if (start != null) {
            query.ge(WikiLog::getTime, start);
        }
        if (end != null) {
            query.le(WikiLog::getTime, end);
        }
        if (!includeHided) {
            query.eq(WikiLog::getHided, false);
        }
        query.orderByDesc(WikiLog::getId)
                .last("LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset));
        return wikiLogMapper.selectList(query);
    }

    @Override
    public List<WikiLog> recentForTarget(String logType, Long targetUserId, boolean includeHided, int limit) {
        LambdaQueryWrapper<WikiLog> query = Wrappers.lambdaQuery();
        if (Strings.isNotBlank(logType)) {
            query.eq(WikiLog::getLogType, logType);
        }
        query.eq(WikiLog::getTargetUserId, targetUserId);
        if (!includeHided) {
            query.eq(WikiLog::getHided, false);
        }
        query.orderByDesc(WikiLog::getId).last("LIMIT " + Math.max(1, limit));
        return wikiLogMapper.selectList(query);
    }

    @Override
    public List<WikiLog> recentByActor(Long actorUserId, boolean includeHided, int limit) {
        if (actorUserId == null) {
            return List.of();
        }
        LambdaQueryWrapper<WikiLog> query = Wrappers.lambdaQuery();
        query.eq(WikiLog::getActorUserId, actorUserId);
        if (!includeHided) {
            query.eq(WikiLog::getHided, false);
        }
        query.orderByDesc(WikiLog::getId).last("LIMIT " + Math.max(1, limit));
        return wikiLogMapper.selectList(query);
    }

    @Override
    public List<WikiLog> recentByIp(String ipOrCidr, int limit) {
        String value = Strings.trimToEmpty(ipOrCidr);
        if (value.isEmpty()) {
            return List.of();
        }
        int capped = Math.max(1, limit);
        if (!value.contains("/")) {
            return wikiLogMapper.selectList(Wrappers.<WikiLog>lambdaQuery()
                    .eq(WikiLog::getIp, value)
                    .orderByDesc(WikiLog::getId)
                    .last("LIMIT " + capped));
        }
        // CIDR：扫描最近日志后内存匹配（demo 规模足够；数据量大时可以加 ip 前缀索引）
        return wikiLogMapper.selectList(Wrappers.<WikiLog>lambdaQuery()
                        .isNotNull(WikiLog::getIp)
                        .orderByDesc(WikiLog::getId)
                        .last("LIMIT " + IP_SCAN_LIMIT))
                .stream()
                .filter(log -> IpAddresses.matches(log.getIp(), value))
                .limit(capped)
                .toList();
    }

    @Override
    public void setHided(long logId, boolean hided) {
        WikiLog log = wikiLogMapper.selectById(logId);
        if (log == null) {
            return;
        }
        log.setHided(hided);
        wikiLogMapper.updateById(log);
    }
}
