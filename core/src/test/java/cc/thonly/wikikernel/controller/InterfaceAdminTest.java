package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 界面管理员（仿 MediaWiki interface-admin）：
 * <ul>
 *     <li>CSS / JS 页面（{@code *.css} / {@code *.js}）默认只有「界面管理员」能编辑 / 创建 / 移动；</li>
 *     <li>普通用户与纯管理员都被降级为「查看源代码」并给出专用提示，保存 403；</li>
 *     <li>该默认规则可被访问控制规则覆盖（精确页面规则优先）；</li>
 *     <li>普通 Wikitext 页面不受影响；组出现在用户权限页。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class InterfaceAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AccessControlService accessControlService;

    @Test
    void onlyInterfaceAdminsCanEditCssAndJsByDefault() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String js = "界面测试" + stamp + ".js";
        String css = "界面测试" + stamp + ".css";
        wikiPageService.editPage(null, js, "var a = 1;");
        wikiPageService.editPage(null, css, ".a {}");

        WikiUser normal = insertUser("界面普通用户" + System.nanoTime());
        WikiUser admin = adminUser();
        WikiUser interfaceAdmin = interfaceAdminUser();

        // 普通用户与纯管理员：编辑页降级为查看源代码 + 专用提示；保存 403
        for (WikiUser user : List.of(normal, admin)) {
            mockMvc.perform(get("/wiki/{title}", js).param("action", "edit").session(sessionOf(user)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("只有界面管理员可以编辑")))
                    .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
            mockMvc.perform(post("/wiki/{title}", js).with(csrf()).session(sessionOf(user)).param("content", "x"))
                    .andExpect(status().isForbidden());
        }

        // 界面管理员：编辑与保存都可用
        mockMvc.perform(get("/wiki/{title}", js).param("action", "edit").session(sessionOf(interfaceAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        mockMvc.perform(post("/wiki/{title}", js).with(csrf()).session(sessionOf(interfaceAdmin))
                        .param("content", "var b = 2;"))
                .andExpect(status().is3xxRedirection());

        // CSS 同样：普通用户不行、界面管理员可以
        mockMvc.perform(post("/wiki/{title}", css).with(csrf()).session(sessionOf(normal)).param("content", "x"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/wiki/{title}", css).with(csrf()).session(sessionOf(interfaceAdmin))
                        .param("content", ".b {}"))
                .andExpect(status().is3xxRedirection());

        // 普通 Wikitext 页面不受影响
        String page = "界面普通页面" + System.nanoTime();
        wikiPageService.editPage(null, page, "内容");
        mockMvc.perform(post("/wiki/{title}", page).with(csrf()).session(sessionOf(normal)).param("content", "改"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void typescriptAndVuePagesAreInterfaceProtected() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String ts = "界面TS" + stamp + ".ts";
        String tsx = "界面TSX" + stamp + ".tsx";
        String vue = "界面Vue" + stamp + ".vue";
        wikiPageService.editPage(null, ts, "export const a: number = 1;");
        wikiPageService.editPage(null, tsx, "export const b = () => <div/>;");
        wikiPageService.editPage(null, vue, "<template><div/></template>");

        WikiUser normal = insertUser("界面TS普通用户" + System.nanoTime());
        WikiUser interfaceAdmin = interfaceAdminUser();
        for (String title : List.of(ts, tsx, vue)) {
            mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(normal)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("只有界面管理员可以编辑")))
                    .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(normal))
                            .param("content", "x"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/wiki/{title}", ts).with(csrf()).session(sessionOf(interfaceAdmin))
                        .param("content", "export const c: number = 3;"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void codePageRuleIsConfigurableAndGroupSeeded() throws Exception {
        String js = "界面可配置" + System.nanoTime() + ".js";
        wikiPageService.editPage(null, js, "var a = 1;");
        WikiUser normal = insertUser("界面配置用户" + System.nanoTime());

        // 内置类型里包含「界面管理员」
        assertTrue(permissionService.allTypes().stream().map(WikiPermissionType::getName).toList()
                .contains(PermissionService.GROUP_INTERFACE_ADMIN), "应内置「界面管理员」用户组");

        // 精确规则覆盖内置默认：该页面 编辑 = 用户
        accessControlService.saveRule(js, AccessAction.EDIT, PermissionService.GROUP_USER);
        try {
            mockMvc.perform(get("/wiki/{title}", js).param("action", "edit").session(sessionOf(normal)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
            mockMvc.perform(post("/wiki/{title}", js).with(csrf()).session(sessionOf(normal))
                            .param("content", "var c = 3;"))
                    .andExpect(status().is3xxRedirection());
        } finally {
            accessControlService.findRule(AccessAction.EDIT, js)
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }
    }

    @Test
    void interfaceAdminGroupAppearsOnUserRightsPage() throws Exception {
        WikiUser admin = adminUser();
        WikiUser target = insertUser("界面权限目标" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", target.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PermissionService.GROUP_INTERFACE_ADMIN)));
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
        WikiUser user = insertUser("界面测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private WikiUser interfaceAdminUser() {
        WikiUser user = insertUser("界面管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_INTERFACE_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
