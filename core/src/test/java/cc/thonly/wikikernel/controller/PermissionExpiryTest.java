package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限有效期（仿 MediaWiki 的「到期时间」）：
 * <ul>
 *     <li>授予用户组时可设置有效期；到期后该组自动失效（权限检查 / canManage / 权限列表立即不再包含）；</li>
 *     <li>记录保留：用户权限页把已过期的组标为「（已过期）」并保留原来的时间输入；</li>
 *     <li>接口 {@code /api/user/permissions} 接收 {@code expiry.<组名>}（datetime-local；空 = 永久），
 *     非法时间返回 400；</li>
 *     <li>权限日志带有效期（{@code grant|组|ISO时间}，页面显示「有效期至 …」）。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PermissionExpiryTest {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiUserPermissionMapper userPermissionMapper;

    @Autowired
    private WikiPermissionTypeMapper permissionTypeMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Test
    void expiredPermissionStopsWorkingAndIsShownAsExpired() throws Exception {
        WikiUser admin = adminUser();
        WikiUser user = insertUser("到期用户" + System.nanoTime());
        LocalDateTime future = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);

        permissionService.replaceFor(user.getId(),
                Map.of(PermissionService.GROUP_ADMIN, future), admin.getId());
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_ADMIN));
        assertTrue(permissionService.canManage(user.getId()));

        // 权限页：「您可以更改的用户组」+ 期限下拉（已有有效期 → 「其他时间」+ 原时间）；还没有「已过期」
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", user.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您可以更改的用户组")))
                .andExpect(content().string(containsString("不会过期")))
                .andExpect(content().string(containsString("其他时间：")))
                .andExpect(content().string(containsString(future.format(INPUT))))
                .andExpect(content().string(containsString("有效期至")))
                .andExpect(content().string(not(containsString("（已过期）"))));

        // 模拟到期：把到期时间改成过去
        WikiPermissionType adminType = permissionTypeMapper.selectOne(Wrappers.<WikiPermissionType>lambdaQuery()
                .eq(WikiPermissionType::getName, PermissionService.GROUP_ADMIN)
                .last("LIMIT 1"));
        WikiUserPermission row = userPermissionMapper.selectOne(Wrappers.<WikiUserPermission>lambdaQuery()
                .eq(WikiUserPermission::getUserId, user.getId())
                .eq(WikiUserPermission::getPermissionType, adminType.getId())
                .last("LIMIT 1"));
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        userPermissionMapper.updateById(row);

        // 到期后：权限立即失效（不能管理、不再出现在权限列表），但权限页显示「已过期」
        assertFalse(permissionService.has(user.getId(), PermissionService.GROUP_ADMIN));
        assertFalse(permissionService.canManage(user.getId()));
        assertFalse(permissionService.namesOf(user.getId()).contains(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", "特殊:权限列表").param("group", PermissionService.GROUP_ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(user.getUsername()))));
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", user.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("（已过期）")));
    }

    @Test
    void apiSavesExpiryAndRejectsInvalid() throws Exception {
        WikiUser admin = adminUser();
        WikiUser target = insertUser("到期接口用户" + System.nanoTime());
        String expiry = LocalDateTime.now().plusYears(1).withSecond(0).withNano(0).format(INPUT);

        mockMvc.perform(post("/api/user/permissions").session(sessionOf(admin))
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN)
                        .param("expiry." + PermissionService.GROUP_ADMIN, expiry))
                .andExpect(jsonPath("$.code").value(200));

        List<PermissionService.Membership> memberships = permissionService.membershipsOf(target.getId());
        assertTrue(memberships.stream().anyMatch(m -> m.name().equals(PermissionService.GROUP_ADMIN)),
                "应包含管理员：" + memberships);
        assertEquals(LocalDateTime.parse(expiry), expiryOf(target.getId(), PermissionService.GROUP_ADMIN));
        assertTrue(permissionService.has(target.getId(), PermissionService.GROUP_ADMIN));

        // 日志带有效期：grant|管理员|ISO
        List<WikiLog> logs = logService.recentForTarget(LogTypeConfig.RIGHTS, target.getId(), true, 5);
        assertTrue(logs.get(0).getDetail().startsWith("grant|" + PermissionService.GROUP_ADMIN + "|"),
                "日志应带有效期：" + logs.get(0).getDetail());

        // 非法有效期 → 400，且不改变已保存的权限
        mockMvc.perform(post("/api/user/permissions").session(sessionOf(admin))
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN)
                        .param("expiry." + PermissionService.GROUP_ADMIN, "不是时间"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(LocalDateTime.parse(expiry),
                expiryOf(target.getId(), PermissionService.GROUP_ADMIN));

        // 不带有效期（留空）= 永久
        mockMvc.perform(post("/api/user/permissions").session(sessionOf(admin))
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(expiryOf(target.getId(), PermissionService.GROUP_ADMIN));
        assertTrue(permissionService.has(target.getId(), PermissionService.GROUP_ADMIN));
    }

    @Test
    void presetExpiryAndReasonAreSavedAndLogged() throws Exception {
        WikiUser admin = adminUser();
        WikiUser target = insertUser("预设期限用户" + System.nanoTime());

        LocalDateTime before = LocalDateTime.now();
        mockMvc.perform(post("/api/user/permissions").session(sessionOf(admin))
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_WIDGET_EDITOR)
                        .param("expiry." + PermissionService.GROUP_WIDGET_EDITOR, "1y")
                        .param("reason", "测试原因"))
                .andExpect(jsonPath("$.code").value(200));

        // 预设「1年」从保存时起算：约一年后
        LocalDateTime expiry = expiryOf(target.getId(), PermissionService.GROUP_WIDGET_EDITOR);
        assertTrue(expiry.isAfter(before.plusDays(364)) && expiry.isBefore(before.plusDays(367)),
                "1年预设应为约一年后：" + expiry);

        // 权限页：勾选 + 「原因：」输入 + 「保存用户组」按钮；
        // 日志：grant|组|ISO|原因（原因 URL 编码），页面显示「（原因：测试原因）」
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", target.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("原因：")))
                .andExpect(content().string(containsString("保存用户组")))
                .andExpect(content().string(containsString("1年")))
                .andExpect(content().string(containsString("（原因：测试原因）")));
        List<WikiLog> logs = logService.recentForTarget(LogTypeConfig.RIGHTS, target.getId(), true, 5);
        assertTrue(logs.get(0).getDetail().startsWith(
                "grant|" + PermissionService.GROUP_WIDGET_EDITOR + "|"), logs.get(0).getDetail());

        // 移除同样记原因：revoke|组|原因
        mockMvc.perform(post("/api/user/permissions").session(sessionOf(admin))
                        .param("username", target.getUsername())
                        .param("reason", "移除原因"))
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(permissionService.has(target.getId(), PermissionService.GROUP_WIDGET_EDITOR));
        List<WikiLog> afterRemoval = logService.recentForTarget(LogTypeConfig.RIGHTS, target.getId(), true, 5);
        assertTrue(afterRemoval.get(0).getDetail().startsWith(
                "revoke|" + PermissionService.GROUP_WIDGET_EDITOR), afterRemoval.get(0).getDetail());
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", target.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("（原因：移除原因）")));
    }

    /** 取某用户某组的到期时间（没有该组则抛异常；null = 永久）。 */
    private LocalDateTime expiryOf(long userId, String group) {
        PermissionService.Membership membership = permissionService.membershipsOf(userId).stream()
                .filter(item -> item.name().equals(group))
                .findFirst()
                .orElseThrow();
        return membership.expiresAt();
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

    private WikiUser adminUser() {
        WikiUser user = insertUser("到期测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
