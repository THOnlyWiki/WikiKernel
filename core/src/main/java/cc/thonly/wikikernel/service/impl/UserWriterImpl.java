package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.UserWriter;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * {@link UserWriter} 的 core 实现：系统账号创建（随机密码 + 用户组）与用户组移除。
 * <p>
 * 名称、组名都由调用方（插件）决定，core 不认识任何具体系统账号的名字
 * （迁移前 core 的 {@code AbuseFilterAccountSeeder} 认识「滥用过滤器」，现已迁到插件）。
 */
@Service
public class UserWriterImpl implements UserWriter {

    private static final Logger log = LoggerFactory.getLogger(UserWriterImpl.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WikiUserMapper userMapper;
    private final PermissionService permissionService;

    public UserWriterImpl(WikiUserMapper userMapper, PermissionService permissionService) {
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    @Override
    public boolean ensureSystemAccount(String username, String groupName) {
        String name = Strings.trimToEmpty(username);
        if (name.isEmpty()) {
            return false;
        }
        boolean exists = userMapper.exists(Wrappers.<WikiUser>lambdaQuery()
                .eq(WikiUser::getUsername, name));
        if (exists) {
            return false;
        }
        WikiUser user = new WikiUser();
        user.setUsername(name);
        user.setPassword(MediaWikiPassword.hash(randomPassword()));
        user.setEmail("");
        user.setCreateAt(LocalDateTime.now());
        userMapper.insert(user);
        if (!Strings.isBlank(groupName)) {
            permissionService.replaceFor(user.getId(), List.of(groupName));
        }
        log.info("已创建系统账号「{}」（用户组：{}）", name, groupName);
        return true;
    }

    @Override
    public boolean revokeGroup(long userId, String groupName) {
        if (userId <= 0 || Strings.isBlank(groupName)) {
            return false;
        }
        if (!permissionService.has(userId, groupName)) {
            return false;
        }
        permissionService.revoke(userId, groupName, null);
        return true;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
