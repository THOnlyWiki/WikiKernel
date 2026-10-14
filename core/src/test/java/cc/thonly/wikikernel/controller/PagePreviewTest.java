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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编辑预览（仿 MediaWiki「显示预览」）：
 * {@code POST /api/pages/preview} 服务端渲染编辑框内容（含脚注、分类栏），不落盘；
 * 未登录 401、无编辑 / 创建权限 403；Wikitext 编辑页有「显示预览」按钮，代码页没有。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PagePreviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private AccessControlService accessControlService;

    @Test
    void previewRendersContentWithoutSaving() throws Exception {
        WikiUser user = insertUser("预览用户" + System.nanoTime());
        String title = "预览页" + System.nanoTime();
        String category = "预览分类" + System.nanoTime();

        mockMvc.perform(post("/api/pages/preview").session(sessionOf(user))
                        .param("title", title)
                        .param("content", "'''粗体''' 与 [[链接]]\n\n== 标题 ==\n\n来源<ref>脚注A</ref>\n\n"
                                + "<references/>\n\n[[分类:" + category + "]]"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.html").value(containsString("<b>粗体</b>")))
                .andExpect(jsonPath("$.data.html").value(containsString("<h2 id=\"标题\">")))
                .andExpect(jsonPath("$.data.html").value(containsString("href=\"/wiki/链接\"")))
                .andExpect(jsonPath("$.data.html").value(containsString("cite_note-1")))
                .andExpect(jsonPath("$.data.html").value(containsString("class=\"catlinks\"")))
                .andExpect(jsonPath("$.data.html").value(containsString(category)));
        // 预览不落盘
        assertTrue(wikiPageService.getByTitle(title).isEmpty(), "预览不应创建页面");
    }

    @Test
    void previewRequiresLoginAndEditPermission() throws Exception {
        String title = "预览权限页" + System.nanoTime();

        // 未登录 → 401
        mockMvc.perform(post("/api/pages/preview").param("title", title).param("content", "x"))
                .andExpect(jsonPath("$.code").value(401));

        // 普通用户对受限页面没有编辑权限 → 403
        WikiUser user = insertUser("预览权限用户" + System.nanoTime());
        wikiPageService.editPage(null, title, "正文");
        WikiAccessRule rule = accessControlService.saveRule(title, AccessAction.EDIT, PermissionService.GROUP_ADMIN);
        try {
            mockMvc.perform(post("/api/pages/preview").session(sessionOf(user))
                            .param("title", title).param("content", "x"))
                    .andExpect(jsonPath("$.code").value(403));
        } finally {
            accessControlService.deleteRule(rule.getId());
        }
    }

    @Test
    void editPageHasPreviewButtonOnlyForWikitext() throws Exception {
        WikiUser user = insertUser("预览按钮用户" + System.nanoTime());
        String title = "预览按钮页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文");

        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-preview-button\"")))
                .andExpect(content().string(containsString("id=\"wiki-preview\"")))
                .andExpect(content().string(containsString("/js/edit-preview.js")));

        String code = "预览代码页" + System.nanoTime() + ".js";
        wikiPageService.editPage(null, code, "let a = 1;");
        mockMvc.perform(get("/wiki/{title}", code).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("wiki-preview-button"))))
                .andExpect(content().string(not(containsString("/js/edit-preview.js"))));
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
