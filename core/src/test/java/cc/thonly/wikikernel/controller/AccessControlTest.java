package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访问控制：默认规则、页面 / 特殊页查看规则、编辑与创建规则、列表防泄露、配置 API 权限。
 * <p>
 * 用例共享上下文数据库，全部使用唯一标题；涉及的全局规则（特殊:版本）用后即删。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class AccessControlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void readRuleHidesPageFromViewerAndLists() throws Exception {
        WikiUser admin = admin("读规则管理员");
        WikiUser actor = insertUser("读规则作者" + System.nanoTime());
        String title = "机密页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "机密内容");
        accessControlService.saveRule(title, AccessAction.READ, PermissionService.GROUP_ADMIN);

        // 匿名：正文 403，但标题不存在的其它页面仍然 404
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isForbidden());
        // 管理员：正常阅读
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("机密内容")));

        // 列表不泄露标题：全部页面 / 搜索 / 最近更改
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面"))
                .andExpect(content().string(not(containsString(title))));
        String keyword = "机密搜索" + System.nanoTime();
        String searchTitle = keyword + "页";
        wikiPageService.editPage(actor, searchTitle, "机密搜索内容");
        accessControlService.saveRule(searchTitle, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mockMvc.perform(get("/wiki/{title}", "特殊:搜索").param("q", keyword))
                .andExpect(content().string(not(containsString(searchTitle))));
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(content().string(not(containsString(title))));
    }

    @Test
    void specialPageReadRuleAlsoCoversAliases() throws Exception {
        WikiUser admin = admin("特殊页规则管理员");
        WikiAccessRule rule = accessControlService.saveRule("特殊:版本", AccessAction.READ, PermissionService.GROUP_ADMIN);
        try {
            mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                    .andExpect(status().isForbidden());
            // 英文别名同样被拦截（统一按规范名判定）
            mockMvc.perform(get("/wiki/{title}", "Special:Version"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/wiki/{title}", "特殊:版本").session(sessionOf(admin)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("已安装的软件")));
        } finally {
            accessControlService.deleteRule(rule.getId());
        }
    }

    @Test
    void movingProtectedPageKeepsReadAndEditRulesAndRevisionIdentity() throws Exception {
        WikiUser admin = admin("保护移动管理员");
        String from = "受限制来源" + System.nanoTime();
        String to = from + "目标";
        var page = wikiPageService.editPage(admin, from, "受限正文");
        accessControlService.saveRule(from, AccessAction.READ, PermissionService.GROUP_ADMIN);
        accessControlService.saveRule(from, AccessAction.EDIT, PermissionService.GROUP_ADMIN);
        accessControlService.saveRule(to, AccessAction.READ, AccessAction.EVERYONE);
        mockMvc.perform(get("/wiki/{title}", from).session(sessionOf(admin))).andExpect(status().isOk());
        mockMvc.perform(post("/api/pages/move").session(sessionOf(admin))
                        .param("from", from).param("to", to).param("leaveRedirect", "true"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(page.getId(), wikiPageService.getByTitle(to).orElseThrow().getId());
        assertEquals(PermissionService.GROUP_ADMIN,
                accessControlService.requiredGroups(AccessAction.EDIT, to));
        mockMvc.perform(get("/wiki/{title}", from)).andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", to)).andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", to).param("action", "history")).andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", to).param("action", "raw")).andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", to).session(sessionOf(admin)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("受限正文")));
    }

    @Test
    void inheritedProtectionFollowsThePageOutOfItsNamespacePrefix() {
        WikiUser admin = admin("前缀移动管理员");
        String prefix = "保护前缀" + System.nanoTime() + "/";
        String from = prefix + "页面";
        String to = "保护前缀之外" + System.nanoTime();
        wikiPageService.editPage(admin, from, "内容");
        accessControlService.saveRule(prefix + "*", AccessAction.READ, PermissionService.GROUP_ADMIN);
        assertNotNull(wikiPageService.movePage(admin, from, to, false));
        assertFalse(accessControlService.canRead(to, null));
        assertTrue(accessControlService.canRead(to, admin));
    }

    @Test
    void moveRollsBackIfTheDestinationCannotHaveExactProtection() throws Exception {
        WikiUser admin = admin("规则回滚管理员");
        String from = "待保留的来源" + System.nanoTime();
        String to = from + "星号*";
        wikiPageService.editPage(admin, from, "内容");
        accessControlService.saveRule(from, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mockMvc.perform(post("/api/pages/move").session(sessionOf(admin))
                        .param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(403));
        assertTrue(wikiPageService.getByTitle(from).isPresent());
        assertTrue(wikiPageService.getByTitle(to).isEmpty());
        assertFalse(accessControlService.canRead(from, null));
    }

    @Test
    void actionApiMoveRequiresReadAndKeepsTheStricterDestinationPolicy() throws Exception {
        WikiUser admin = admin("Action API 移动管理员");
        WikiUser member = insertUser("Action API 移动成员" + System.nanoTime());
        String from = "Action API 受保护页" + System.nanoTime();
        String prefix = "Action API 目标" + System.nanoTime() + "/";
        String to = prefix + "页面";
        wikiPageService.editPage(admin, from, "保护内容");
        accessControlService.saveRule(from, AccessAction.READ,
                PermissionService.GROUP_ADMIN + "|" + PermissionService.GROUP_BUREAUCRAT);
        accessControlService.saveRule(prefix + "*", AccessAction.READ, PermissionService.GROUP_ADMIN);
        performActionApiMove(sessionOf(member), from, to)
                .andExpect(jsonPath("$.error.code").value("permissiondenied"));
        assertTrue(wikiPageService.getByTitle(from).isPresent());
        performActionApiMove(sessionOf(admin), from, to)
                .andExpect(jsonPath("$.move.to").value(to));
        assertEquals(PermissionService.GROUP_ADMIN, accessControlService.requiredGroups(AccessAction.READ, to));
        assertFalse(accessControlService.canRead(to, member));
    }

    private org.springframework.test.web.servlet.ResultActions performActionApiMove(MockHttpSession session, String from, String to)
            throws Exception {
        String response = mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens"))
                .andReturn().getResponse().getContentAsString();
        String token = new tools.jackson.databind.ObjectMapper().readTree(response)
                .path("query").path("tokens").path("csrftoken").asString();
        return mockMvc.perform(post("/api.php").session(session)
                .param("action", "move").param("token", token).param("noredirect", "1")
                .param("from", from).param("to", to));
    }

    @Test
    void editRuleShowsSourceForNormalUserAndAllowsBureaucrat() throws Exception {
        WikiUser user = insertUser("编辑规则用户" + System.nanoTime());
        String title = "只读页" + System.nanoTime();
        wikiPageService.editPage(user, title, "只读内容");
        accessControlService.saveRule(title, AccessAction.EDIT, PermissionService.GROUP_BUREAUCRAT);

        // 普通用户：编辑页降级为“查看源代码”，提示无编辑权限；直接 POST 403
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您没有权限编辑本页面")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user))
                        .param("content", "越权修改"))
                .andExpect(status().isForbidden());

        // 行政员：可以编辑
        WikiUser bureaucrat = insertUser("编辑规则行政员" + System.nanoTime());
        permissionService.replaceFor(bureaucrat.getId(), List.of(PermissionService.GROUP_BUREAUCRAT));
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(bureaucrat)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
    }

    @Test
    void createRuleUsesLongestPrefixAndBlocksSave() throws Exception {
        WikiUser admin = admin("创建规则管理员");
        WikiUser user = insertUser("创建规则用户" + System.nanoTime());
        String prefix = "帮助" + System.nanoTime() + ":";
        accessControlService.saveRule(prefix + "*", AccessAction.CREATE, PermissionService.GROUP_ADMIN);
        String newTitle = prefix + "新页面";

        mockMvc.perform(get("/wiki/{title}", newTitle).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您没有权限创建本页面")));
        mockMvc.perform(post("/wiki/{title}", newTitle).with(csrf()).session(sessionOf(user))
                        .param("content", "越权创建"))
                .andExpect(status().isForbidden());

        // 管理员可以创建
        mockMvc.perform(post("/wiki/{title}", newTitle).with(csrf()).session(sessionOf(admin))
                        .param("content", "管理员创建"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", newTitle).session(sessionOf(admin)))
                .andExpect(content().string(containsString("管理员创建")));
    }

    @Test
    void accessRuleApiRequiresManageAndSavesRules() throws Exception {
        WikiUser admin = admin("配置接口管理员");
        WikiUser user = insertUser("配置接口用户" + System.nanoTime());
        String target = "接口规则页" + System.nanoTime();

        // 匿名 401，普通用户 403
        mockMvc.perform(post("/api/access-rules")
                        .param("target", target).param("action", "read").param("groups", "*"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/access-rules").session(sessionOf(user))
                        .param("target", target).param("action", "read").param("groups", "*"))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员保存 → 页面展示 → 删除
        mockMvc.perform(post("/api/access-rules").session(sessionOf(admin))
                        .param("target", target).param("action", "read")
                        .param("groups", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("访问规则已保存"));

        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("默认规则（目标 *）")))
                .andExpect(content().string(containsString(target)))
                .andExpect(content().string(containsString("access-rule-form")))
                .andExpect(content().string(containsString("<datalist id=\"access-target-list\">")));
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("只有管理员或行政员可以配置权限规则")))
                .andExpect(content().string(not(containsString("access-rule-form"))));

        Long ruleId = accessControlService.findRule(AccessAction.READ, target).orElseThrow().getId();
        mockMvc.perform(post("/api/access-rules/delete").session(sessionOf(admin))
                        .param("id", ruleId.toString()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("访问规则已删除"));
    }

    // ==================== 工具 ====================

    private WikiUser admin(String prefix) {
        WikiUser admin = insertUser(prefix + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        return admin;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
