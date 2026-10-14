package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限服务：内置类型种子、注册默认组、整体替换、未知类型拒绝、权限日志。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PermissionServiceTest {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Test
    void seedsBuiltInPermissionTypes() {
        List<String> names = permissionService.allTypes().stream().map(WikiPermissionType::getName).toList();

        assertTrue(names.containsAll(List.of(
                PermissionService.GROUP_USER,
                PermissionService.GROUP_AUTOCONFIRMED,
                PermissionService.GROUP_ADMIN,
                PermissionService.GROUP_BUREAUCRAT)), names.toString());
    }

    @Test
    void grantDefaultAndReplacePermissions() {
        long userId = 9001L;

        permissionService.grantDefault(userId);
        // 注册默认只有基础组；「自动确定用户」改为编辑满 50 次自动授予
        assertEquals(Set.of(PermissionService.GROUP_USER), permissionService.namesOf(userId));

        permissionService.replaceFor(userId, List.of(
                PermissionService.GROUP_ADMIN, PermissionService.GROUP_BUREAUCRAT));
        assertEquals(Set.of(PermissionService.GROUP_USER,
                PermissionService.GROUP_ADMIN, PermissionService.GROUP_BUREAUCRAT), permissionService.namesOf(userId));
        assertTrue(permissionService.has(userId, PermissionService.GROUP_ADMIN));

        // 基础组必须保留；自动确定用户是普通可选组，未勾选就被替换掉
        permissionService.replaceFor(userId, List.of());
        assertEquals(Set.of(PermissionService.GROUP_USER), permissionService.namesOf(userId));
        assertFalse(permissionService.has(userId, PermissionService.GROUP_ADMIN));
        assertFalse(permissionService.has(userId, PermissionService.GROUP_AUTOCONFIRMED));
    }

    @Test
    void allUserPermissionsGroupsByUser() {
        long first = 9101L;
        long second = 9102L;
        permissionService.replaceFor(first, List.of(PermissionService.GROUP_ADMIN));
        permissionService.grantDefault(second);

        Map<Long, Set<String>> all = permissionService.allUserPermissions();

        assertEquals(Set.of(PermissionService.GROUP_USER, PermissionService.GROUP_ADMIN), all.get(first));
        assertEquals(Set.of(PermissionService.GROUP_USER), all.get(second));
    }

    @Test
    void rightsChangesAreLoggedToGenericLog() {
        long target = 9201L;
        long actor = 9202L;

        permissionService.replaceFor(target, List.of(PermissionService.GROUP_ADMIN), actor);
        permissionService.replaceFor(target, List.of(), actor);

        List<WikiLog> logs = logService.recentForTarget("rights", target, true, 10);
        assertEquals(2, logs.size());
        assertEquals(actor, logs.get(0).getActorUserId());
        assertEquals("rights", logs.get(0).getLogType());
        assertTrue(logs.get(0).getDetail().startsWith(PermissionService.RIGHTS_REVOKE_PREFIX));
        assertTrue(logs.get(1).getDetail().startsWith(PermissionService.RIGHTS_GRANT_PREFIX));
    }

    @Test
    void unknownPermissionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> permissionService.replaceFor(9002L, List.of("不存在的权限")));
    }
}
