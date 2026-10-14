package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LocalSettings.yaml（{@code wikikernel.permissions.rules}）的访问规则在启动时导入并立即生效。
 * 独立上下文（自定义 properties），不会影响其它测试。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.permissions.rules[0].target=导入配置页",
        "wikikernel.permissions.rules[0].action=read",
        "wikikernel.permissions.rules[0].groups=管理员"
})
@AutoConfigureMockMvc
class AccessRuleImportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void localSettingsRulesAreImportedOnStartupAndEnforced() throws Exception {
        // 启动时导入，规则已生效
        assertEquals("管理员", accessControlService.requiredGroups(AccessAction.READ, "导入配置页"));

        WikiUser actor = insertUser("导入页作者");
        wikiPageService.editPage(actor, "导入配置页", "导入测试内容");

        // 匿名被拦截
        mockMvc.perform(get("/wiki/{title}", "导入配置页"))
                .andExpect(status().isForbidden());

        // 管理员可读
        WikiUser admin = insertUser("导入页管理员");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", "导入配置页").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("导入测试内容")));
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username + System.nanoTime());
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
