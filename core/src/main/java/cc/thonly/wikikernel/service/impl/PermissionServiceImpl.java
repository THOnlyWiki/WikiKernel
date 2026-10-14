package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 权限实现：绑定存 {@code wiki_user_permission}（含每组独立的有效期 {@code expires_at}）。
 * <p>
 * 有效期语义：{@code expiresAt == null} = 永久；到点后该组自动失效（{@link #namesOf} 不再返回，
 * 访问控制 / 管理判断全部随之失效），但记录保留，权限页显示「已过期」并可重新授予。
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    private final WikiPermissionTypeMapper permissionTypeMapper;
    private final WikiUserPermissionMapper userPermissionMapper;
    private final LogService logService;

    public PermissionServiceImpl(WikiPermissionTypeMapper permissionTypeMapper,
                                 WikiUserPermissionMapper userPermissionMapper,
                                 LogService logService) {
        this.permissionTypeMapper = permissionTypeMapper;
        this.userPermissionMapper = userPermissionMapper;
        this.logService = logService;
    }

    @Override
    public List<WikiPermissionType> allTypes() {
        return permissionTypeMapper.selectList(Wrappers.<WikiPermissionType>lambdaQuery()
                .orderByAsc(WikiPermissionType::getId));
    }

    @Override
    public Set<String> namesOf(Long userId) {
        Set<String> names = new LinkedHashSet<>();
        for (Membership membership : membershipsOf(userId)) {
            if (membership.active()) {
                names.add(membership.name());
            }
        }
        return names;
    }

    @Override
    public List<Membership> membershipsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<WikiPermissionType> types = allTypes();
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (WikiPermissionType type : types) {
            nameById.put(type.getId(), type.getName());
        }
        Map<String, LocalDateTime> expiryByName = new LinkedHashMap<>();
        for (WikiUserPermission permission : userPermissionMapper.selectList(
                Wrappers.<WikiUserPermission>lambdaQuery().eq(WikiUserPermission::getUserId, userId))) {
            String name = nameById.get(permission.getPermissionType());
            if (name != null) {
                expiryByName.putIfAbsent(name, permission.getExpiresAt());
            }
        }
        // 按类型表顺序输出，保证展示稳定
        List<Membership> memberships = new ArrayList<>();
        for (WikiPermissionType type : types) {
            if (expiryByName.containsKey(type.getName())) {
                memberships.add(new Membership(type.getName(), expiryByName.get(type.getName())));
            }
        }
        return memberships;
    }

    @Override
    @Transactional
    public void replaceFor(Long userId, Map<String, LocalDateTime> memberships, Long actorUserId) {
        replaceFor(userId, memberships, actorUserId, null);
    }

    @Override
    @Transactional
    public void replaceFor(Long userId, Map<String, LocalDateTime> memberships, Long actorUserId, String reason) {
        Map<String, LocalDateTime> before = new LinkedHashMap<>();
        for (Membership membership : membershipsOf(userId)) {
            before.put(membership.name(), membership.expiresAt());
        }
        Map<String, LocalDateTime> requested = new LinkedHashMap<>();
        if (memberships != null) {
            memberships.forEach((name, expiry) -> {
                if (name != null && !name.isBlank()) {
                    requested.put(name, expiry);
                }
            });
        }
        // 基础组始终保留（沿用原有有效期，通常为永久）
        for (String implicit : IMPLICIT_GROUPS) {
            requested.putIfAbsent(implicit, before.get(implicit));
        }
        store(userId, requested);
        logDiff(userId, actorUserId, before, requested, reason);
    }

    @Override
    public void grantDefault(Long userId) {
        // 注册默认组：仅基础组「用户」；「自动确定用户」改为编辑满 AUTO_CONFIRM_EDITS 次自动授予
        Map<String, LocalDateTime> memberships = new LinkedHashMap<>();
        for (String name : DEFAULT_GROUPS) {
            memberships.put(name, null);
        }
        store(userId, memberships);
    }

    @Override
    public boolean has(Long userId, String permissionName) {
        return userId != null && permissionName != null && namesOf(userId).contains(permissionName);
    }

    @Override
    public Map<Long, Set<String>> allUserPermissions() {
        List<WikiPermissionType> types = allTypes();
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Set<Long>> typeIdsByUser = new LinkedHashMap<>();
        for (WikiUserPermission permission : userPermissionMapper.selectList(null)) {
            if (permission.getExpiresAt() != null && !permission.getExpiresAt().isAfter(now)) {
                continue; // 已过期的不算有效权限
            }
            typeIdsByUser.computeIfAbsent(permission.getUserId(), key -> new LinkedHashSet<>())
                    .add(permission.getPermissionType());
        }
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        typeIdsByUser.forEach((userId, typeIds) -> {
            Set<String> names = new LinkedHashSet<>();
            // 按类型表的顺序输出，保证列表页展示稳定
            for (WikiPermissionType type : types) {
                if (typeIds.contains(type.getId())) {
                    names.add(type.getName());
                }
            }
            result.put(userId, names);
        });
        return result;
    }

    /** 写入用户权限绑定（不改日志）：组 → 过期时间（null = 永久）。 */
    private void store(Long userId, Map<String, LocalDateTime> requested) {
        List<WikiPermissionType> types = allTypes();
        Map<String, Long> idByName = new LinkedHashMap<>();
        for (WikiPermissionType type : types) {
            idByName.put(type.getName(), type.getId());
        }
        for (String name : requested.keySet()) {
            if (!idByName.containsKey(name)) {
                throw new IllegalArgumentException("未知的权限: " + name);
            }
        }

        userPermissionMapper.delete(Wrappers.<WikiUserPermission>lambdaQuery()
                .eq(WikiUserPermission::getUserId, userId));
        // 按类型顺序插入，保证读取顺序稳定
        for (WikiPermissionType type : types) {
            if (!requested.containsKey(type.getName())) {
                continue;
            }
            WikiUserPermission permission = new WikiUserPermission();
            permission.setUserId(userId);
            permission.setPermissionType(type.getId());
            permission.setExpiresAt(requested.get(type.getName()));
            userPermissionMapper.insert(permission);
        }
    }

    /** 记录「授予 / 移除 / 有效期变更」差异到通用日志（基础组不算变更）；{@code reason} 可空。 */
    private void logDiff(Long userId, Long actorUserId,
                         Map<String, LocalDateTime> before, Map<String, LocalDateTime> after,
                         String reason) {
        for (Map.Entry<String, LocalDateTime> entry : after.entrySet()) {
            String name = entry.getKey();
            if (IMPLICIT_GROUPS.contains(name)) {
                continue;
            }
            boolean added = !before.containsKey(name);
            boolean expiryChanged = !added && !Objects.equals(before.get(name), entry.getValue());
            if (added || expiryChanged) {
                logService.record(LogTypeConfig.RIGHTS, actorUserId, null, userId,
                        grantDetail(name, entry.getValue(), reason));
            }
        }
        for (String name : before.keySet()) {
            if (after.containsKey(name) || IMPLICIT_GROUPS.contains(name)) {
                continue;
            }
            logService.record(LogTypeConfig.RIGHTS, actorUserId, null, userId, revokeDetail(name, reason));
        }
    }

    /**
     * 日志详情（授予）：{@code grant|组} / {@code grant|组|过期时间（ISO）}；
     * 带原因时 {@code grant|组||原因} / {@code grant|组|过期时间|原因}（原因 URL 编码，避免 {@code |} 冲突）。
     */
    private static String grantDetail(String name, LocalDateTime expiry, String reason) {
        String encodedReason = encodeReason(reason);
        StringBuilder detail = new StringBuilder(RIGHTS_GRANT_PREFIX).append(name);
        if (expiry != null || !encodedReason.isEmpty()) {
            detail.append('|');
            if (expiry != null) {
                detail.append(expiry.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }
            if (!encodedReason.isEmpty()) {
                detail.append('|').append(encodedReason);
            }
        }
        return detail.toString();
    }

    /** 日志详情（移除）：{@code revoke|组}，带原因时 {@code revoke|组|原因}。 */
    private static String revokeDetail(String name, String reason) {
        String encodedReason = encodeReason(reason);
        return encodedReason.isEmpty() ? RIGHTS_REVOKE_PREFIX + name
                : RIGHTS_REVOKE_PREFIX + name + "|" + encodedReason;
    }

    /** 原因 URL 编码（空原因 = 空串，日志里不占位）。 */
    private static String encodeReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        return value.isEmpty() ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
