package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.SetupProperties;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 安装向导写入的初始账号（{@code wikikernel.setup.initial-accounts}）：
 * 启动时若账号不存在则创建并直接授予配置里的用户组（管理员 / 行政员）。
 * 已存在的账号不会被覆盖（密码 / 权限都不动）。
 * <p>
 * 顺序在权限类型种子（2）与权限引导（3）之后。
 */
@Component
@Order(4)
public class SetupAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SetupAccountSeeder.class);

    private final SetupProperties properties;
    private final UserService userService;
    private final WikiUserMapper wikiUserMapper;
    private final PermissionService permissionService;

    public SetupAccountSeeder(SetupProperties properties,
                              UserService userService,
                              WikiUserMapper wikiUserMapper,
                              PermissionService permissionService) {
        this.properties = properties;
        this.userService = userService;
        this.wikiUserMapper = wikiUserMapper;
        this.permissionService = permissionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SetupProperties.InitialAccount account : properties.getInitialAccounts()) {
            String username = Strings.trimToEmpty(account.getUsername());
            if (username.isEmpty()) {
                continue;
            }
            if (userService.findByUsername(username).isPresent()) {
                continue; // 已存在：不覆盖密码 / 权限
            }
            String passwordHash = Strings.trimToEmpty(account.effectivePasswordHash());
            if (passwordHash.isEmpty()) {
                log.warn("安装向导：初始账号「{}」缺少 password-hash，已跳过", username);
                continue;
            }
            if (!MediaWikiPassword.isRecognizedFormat(passwordHash)) {
                log.warn("安装向导：初始账号「{}」的 password-hash 不是可识别的哈希（MediaWiki 格式，如 :pbkdf2:…），"
                        + "该账号将无法登录", username);
            }
            WikiUser user = new WikiUser();
            user.setUsername(username);
            user.setPassword(passwordHash);
            user.setEmail(Strings.trimToEmpty(account.getEmail()));
            user.setCreateAt(LocalDateTime.now());
            wikiUserMapper.insert(user);

            Collection<String> groups = new ArrayList<>(account.getGroups());
            if (groups.isEmpty()) {
                groups = List.of(PermissionService.GROUP_ADMIN);
            }
            permissionService.replaceFor(user.getId(), groups);
            log.info("安装向导：已创建初始账号「{}」（用户组：{}）", username, String.join("、", groups));
        }
    }
}
