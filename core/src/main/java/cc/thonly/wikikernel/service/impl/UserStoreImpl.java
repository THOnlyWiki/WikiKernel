package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用户访问桥：把 core 的用户读取能力暴露给插件（{@code common.service.UserStore}）。
 */
@Service
public class UserStoreImpl implements UserStore {

    private final WikiUserMapper userMapper;
    private final UserService userService;
    private final PermissionService permissionService;

    public UserStoreImpl(WikiUserMapper userMapper, UserService userService, PermissionService permissionService) {
        this.userMapper = userMapper;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Optional<UserRef> byId(long userId) {
        WikiUser user = userMapper.selectById(userId);
        return user == null ? Optional.empty() : Optional.of(ref(user));
    }

    @Override
    public Optional<UserRef> byName(String username) {
        return userService.findByUsername(username).map(UserStoreImpl::ref);
    }

    @Override
    public Map<Long, String> names(Collection<Long> userIds) {
        return userService.usernames(userIds == null ? List.of() : userIds);
    }

    @Override
    public Map<Long, String> avatars(Collection<Long> userIds) {
        return userService.avatars(userIds == null ? List.of() : userIds);
    }

    @Override
    public Set<String> groups(long userId) {
        return permissionService.namesOf(userId);
    }

    @Override
    public boolean exists(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return userMapper.selectCount(Wrappers.<WikiUser>lambdaQuery()
                .eq(WikiUser::getUsername, username.trim())) > 0;
    }

    @Override
    public List<UserRef> randomWithAvatar(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long total = userMapper.selectCount(Wrappers.<WikiUser>lambdaQuery()
                .isNotNull(WikiUser::getAvatar)
                .ne(WikiUser::getAvatar, ""));
        if (total <= 0) {
            return List.of();
        }
        int wanted = (int) Math.min(limit, total);
        // 随机起点 + 一个窗口内的用户：只查一次，避免「取全表再洗牌」
        int start = (int) ThreadLocalRandom.current().nextLong(Math.max(total - wanted, 0) + 1);
        return userMapper.selectList(Wrappers.<WikiUser>lambdaQuery()
                        .isNotNull(WikiUser::getAvatar)
                        .ne(WikiUser::getAvatar, "")
                        .orderByAsc(WikiUser::getId)
                        .last("limit " + wanted + " offset " + start))
                .stream()
                .map(UserStoreImpl::ref)
                .toList();
    }

    private static UserRef ref(WikiUser user) {
        return new UserRef(user.getId() == null ? 0 : user.getId(),
                user.getUsername() == null ? "" : user.getUsername(),
                user.getAvatar() == null ? "" : user.getAvatar(),
                Boolean.TRUE.equals(user.getEmailConfirmed()));
    }

    /** 批量取用户名（保留此方法便于将来批量优化；当前直接委托 UserService）。 */
    Map<Long, String> namesOf(List<Long> ids) {
        Map<Long, String> result = new LinkedHashMap<>(userService.usernames(ids));
        return result;
    }
}
