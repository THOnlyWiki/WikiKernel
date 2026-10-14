package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑自动授予「自动确定用户」：
 * <ul>
 *     <li>注册不再默认授予（默认组只有基础组「用户」）；</li>
 *     <li>编辑满 {@link PermissionService#AUTO_CONFIRM_EDITS}（50）次后由系统自动授予，并写入权限日志；</li>
 *     <li>已拥有后再编辑不会重复写日志。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class AutoConfirmTest {

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void autoconfirmedGrantedAfterFiftyEdits() {
        WikiUser user = insertUser("自动确认用户" + System.nanoTime());
        permissionService.grantDefault(user.getId());
        assertFalse(permissionService.has(user.getId(), PermissionService.GROUP_AUTOCONFIRMED),
                "注册不再默认授予「自动确定用户」");

        String title = "自动确认测试页" + System.nanoTime();
        for (int i = 1; i < PermissionService.AUTO_CONFIRM_EDITS; i++) {
            wikiPageService.editPage(user, title, "第 " + i + " 版");
        }
        assertFalse(permissionService.has(user.getId(), PermissionService.GROUP_AUTOCONFIRMED),
                "编辑 " + (PermissionService.AUTO_CONFIRM_EDITS - 1) + " 次时还不授予");

        wikiPageService.editPage(user, title, "第 " + PermissionService.AUTO_CONFIRM_EDITS + " 版");
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_AUTOCONFIRMED),
                "编辑满 " + PermissionService.AUTO_CONFIRM_EDITS + " 次自动授予");

        // 再编辑不会重复写权限日志：只有一条 grant|自动确定用户
        long grants = logService.recentForTarget(LogTypeConfig.RIGHTS, user.getId(), true, 20).stream()
                .filter(log -> log.getDetail() != null
                        && log.getDetail().startsWith(PermissionService.RIGHTS_GRANT_PREFIX
                        + PermissionService.GROUP_AUTOCONFIRMED))
                .count();
        assertEquals(1, grants, "自动授予只记一条权限日志");
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
