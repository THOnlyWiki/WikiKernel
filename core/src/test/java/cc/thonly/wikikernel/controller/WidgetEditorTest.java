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
 * 小部件编辑者（仿 MediaWiki 的 editwidgets）：
 * <ul>
 *     <li>「微件:」命名空间页面默认只有「小部件编辑者」能编辑 / 创建 / 移动（管理员不豁免）；</li>
 *     <li>普通用户与纯管理员都被降级为「查看源代码」并给出专用提示，保存 403；</li>
 *     <li>该默认规则可被访问控制规则覆盖（如前缀规则 {@code 微件:*}，在 特殊:权限配置 添加）；</li>
 *     <li>组内置在权限类型里，可在 特殊:用户权限 授予。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WidgetEditorTest {

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
    void onlyWidgetEditorsCanEditWidgetsByDefault() throws Exception {
        String title = "微件:默认权限" + System.nanoTime();
        wikiPageService.editPage(null, title, "<div>原</div>");
        WikiUser normal = insertUser("小部件普通用户" + System.nanoTime());
        WikiUser admin = adminUser();

        // 普通用户与纯管理员：编辑页降级为查看源代码 + 专用提示；保存 403
        for (WikiUser denied : List.of(normal, admin)) {
            mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(denied)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("只有小部件编辑者可以编辑")))
                    .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(denied))
                            .param("content", "<div>偷改</div>"))
                    .andExpect(status().isForbidden());
        }

        // 小部件编辑者：编辑与保存都可用
        WikiUser widgetEditor = widgetEditorUser();
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(widgetEditor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(widgetEditor))
                        .param("content", "<div>编辑者改</div>"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void widgetRuleIsConfigurableThroughAccessControl() throws Exception {
        String title = "微件:可配置" + System.nanoTime();
        wikiPageService.editPage(null, title, "<div>原</div>");
        WikiUser normal = insertUser("小部件配置用户" + System.nanoTime());

        // 默认：普通用户不能编辑
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(normal))
                        .param("content", "<div>偷改</div>"))
                .andExpect(status().isForbidden());

        // 前缀规则覆盖内置默认：微件:* 编辑 = 用户（等价于在 特殊:权限配置 里加一条规则）
        accessControlService.saveRule("微件:*", AccessAction.EDIT, PermissionService.GROUP_USER);
        try {
            mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(normal)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(normal))
                            .param("content", "<div>普通用户改</div>"))
                    .andExpect(status().is3xxRedirection());
        } finally {
            accessControlService.findRule(AccessAction.EDIT, title)
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }
    }

    @Test
    void widgetEditorGroupIsSeededAndAppearsOnUserRightsPage() throws Exception {
        assertTrue(permissionService.allTypes().stream().map(WikiPermissionType::getName).toList()
                .contains(PermissionService.GROUP_WIDGET_EDITOR), "应内置「小部件编辑者」用户组");

        WikiUser admin = adminUser();
        WikiUser target = insertUser("小部件权限目标" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", target.getUsername()).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PermissionService.GROUP_WIDGET_EDITOR)));
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
        WikiUser user = insertUser("小部件测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private WikiUser widgetEditorUser() {
        WikiUser user = insertUser("小部件编辑者" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_WIDGET_EDITOR));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
