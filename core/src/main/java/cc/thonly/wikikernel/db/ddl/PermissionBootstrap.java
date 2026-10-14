package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.PermissionProperties;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 首次搭建引导：把 {@code wikikernel.permissions.bootstrap-admins} 里配置的用户名
 * 提升为“管理员 + 行政员”（在原有权限基础上加，不覆盖、不降权）。
 * <p>
 * 解决“第一个管理员从哪来”的问题：配置一次、启动后即可在权限编辑页给别人发权限。
 * 顺序排在权限类型种子（2）之后。
 */
@Component
@Order(3)
public class PermissionBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionBootstrap.class);

    private final PermissionProperties properties;
    private final UserService userService;
    private final PermissionService permissionService;

    public PermissionBootstrap(PermissionProperties properties,
                               UserService userService,
                               PermissionService permissionService) {
        this.properties = properties;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String configured : properties.getBootstrapAdmins()) {
            String username = Strings.trimToEmpty(configured);
            if (username.isEmpty()) {
                continue;
            }
            userService.findByUsername(username).ifPresentOrElse(user -> {
                Set<String> names = new LinkedHashSet<>(permissionService.namesOf(user.getId()));
                boolean changed = names.add(PermissionService.GROUP_ADMIN);
                changed |= names.add(PermissionService.GROUP_BUREAUCRAT);
                if (changed) {
                    permissionService.replaceFor(user.getId(), names);
                    log.info("权限引导：已将用户「{}」提升为管理员 + 行政员", username);
                }
            }, () -> log.warn("权限引导：用户不存在，跳过「{}」", username));
        }
    }
}
