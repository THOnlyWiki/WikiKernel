package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微件命名空间（Widgets 扩展已拆成插件 {@code widget}）：
 * <ul>
 *     <li>{@code {{#widget:名称|参数=值}}} 的**调用**由插件提供，core 未加载插件时按原文显示；</li>
 *     <li>「微件:」页面仍是 core 的 HTML 代码页（默认只有「小部件编辑者」可编辑，对应
 *     {@code editwidgets}；管理员不豁免）；</li>
 *     <li>移动页面到微件命名空间需要目标权限。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WidgetPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Test
    void widgetCallFallsBackToRawTextWithoutPlugin() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String widget = "测试框" + suffix;
        wikiPageService.editPage(null, "微件:" + widget,
                "<div class=\"widget-box\" data-src=\"<!--{$src|escape:'html'}-->\">"
                        + "框：<!--{$title|default:'无标题'}--><script>window.__widget = 1;</script></div>");

        String page = "微件调用页" + suffix;
        wikiPageService.editPage(null, page, "前文\n\n{{#widget:" + widget + "|src=a&b|title=你好}}\n\n后文");

        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                // 插件未加载：未知解析器函数按原文显示（页面其余内容照常渲染）
                .andExpect(content().string(containsString("{{#widget:" + widget)))
                .andExpect(content().string(not(containsString("widget-box"))))
                .andExpect(content().string(containsString("前文")))
                .andExpect(content().string(containsString("后文")));
    }

    @Test
    void widgetPageIsHtmlCodePageAndWidgetEditorOnly() throws Exception {
        String title = "微件:示例" + System.nanoTime();
        wikiPageService.editPage(null, title, "<div>初始代码</div>");

        // 匿名查看：HTML 高亮预览 + 微件说明（没有 JS/CSS 的缓存提示）
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"html\"")))
                .andExpect(content().string(containsString("/vendor/codemirror/mode-htmlmixed.min.js")))
                .andExpect(content().string(containsString("本页是「微件」代码页")))
                .andExpect(content().string(not(containsString("清除浏览器缓存"))));

        // 普通用户与管理员（管理员不豁免）：编辑页降级为「查看源代码」，直接保存 403
        WikiUser user = insertUser("微件普通用户" + System.nanoTime());
        WikiUser admin = insertUser("微件管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        for (WikiUser denied : List.of(user, admin)) {
            mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(denied)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("只有小部件编辑者可以编辑")))
                    .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(denied))
                            .param("content", "<script>alert(1)</script>"))
                    .andExpect(status().isForbidden());
        }

        // 小部件编辑者：HTML 编辑器 + 微件提示，可以保存
        WikiUser widgetEditor = insertUser("微件编辑者" + System.nanoTime());
        permissionService.replaceFor(widgetEditor.getId(), List.of(PermissionService.GROUP_WIDGET_EDITOR));
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(widgetEditor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")))
                .andExpect(content().string(containsString("data-language=\"html\"")))
                .andExpect(content().string(containsString("微件页：HTML + Smarty 子集语法")));
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(widgetEditor))
                        .param("content", "<div>编辑者改的</div>").param("comment", "测试"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(widgetEditor)))
                .andExpect(content().string(containsString("编辑者改的")));
    }

    @Test
    void moveIntoWidgetNamespaceNeedsTargetPermission() throws Exception {
        WikiUser user = insertUser("微件移动用户" + System.nanoTime());
        String from = "可移动页" + System.nanoTime();
        wikiPageService.editPage(user, from, "内容");

        // 普通用户：源页面能移动，但目标（微件命名空间）没有创建权限 → 拒绝
        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", "微件:偷渡" + System.nanoTime()))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("没有权限把页面移动")));

        // 管理员（不豁免）：同样不能移入
        WikiUser admin = insertUser("微件移动管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(post("/api/pages/move").session(sessionOf(admin))
                        .param("from", from).param("to", "微件:管理员移入" + System.nanoTime()))
                .andExpect(jsonPath("$.code").value(403));

        // 小部件编辑者：可以移入
        WikiUser widgetEditor = insertUser("微件移动编辑者" + System.nanoTime());
        permissionService.replaceFor(widgetEditor.getId(), List.of(PermissionService.GROUP_WIDGET_EDITOR));
        String to = "微件:移入" + System.nanoTime();
        mockMvc.perform(post("/api/pages/move").session(sessionOf(widgetEditor))
                        .param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/wiki/{title}", to).session(sessionOf(widgetEditor)))
                .andExpect(content().string(containsString("内容")));
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

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
