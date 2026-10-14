package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 页面保护与移动：未登录/受保护页面显示查看源代码；只有管理员能编辑受保护页面；移动保留历史。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PageProtectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void protectedPageOnlyAdminsCanEdit() throws Exception {
        String title = "保护测试页" + System.nanoTime();
        insertPage(title, "受保护内容", true);

        // 匿名：查看源代码（不是编辑器、不是跳登录）
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面已被保护")))
                .andExpect(content().string(containsString("受保护内容")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));

        // 普通用户：查看源代码
        WikiUser normal = insertUser("保护普通用户" + System.nanoTime());
        MockHttpSession normalSession = sessionOf(normal);
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(normalSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面已被保护")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));

        // 普通用户直接保存：403
        mockMvc.perform(post("/wiki/{title}", title)
                        .session(normalSession).with(csrf())
                        .param("content", "试图修改"))
                .andExpect(status().isForbidden());

        // 管理员：可以打开编辑器并保存
        WikiUser admin = adminUser();
        MockHttpSession adminSession = sessionOf(admin);
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));

        mockMvc.perform(post("/wiki/{title}", title)
                        .session(adminSession).with(csrf())
                        .param("content", "管理员修改"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(content().string(containsString("管理员修改")));
    }

    @Test
    void protectApiTogglesProtection() throws Exception {
        String title = "保护开关页" + System.nanoTime();
        insertPage(title, "内容", false);

        // 匿名 401
        mockMvc.perform(post("/api/pages/protect").param("title", title).param("protected", "true"))
                .andExpect(jsonPath("$.code").value(401));

        // 普通用户 403
        WikiUser normal = insertUser("保护开关普通" + System.nanoTime());
        mockMvc.perform(post("/api/pages/protect").session(sessionOf(normal))
                        .param("title", title).param("protected", "true"))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员开启 + 解除
        MockHttpSession adminSession = sessionOf(adminUser());
        mockMvc.perform(post("/api/pages/protect").session(adminSession)
                        .param("title", title).param("protected", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已开启编辑保护"));
        assertTrue(wikiPageService.getByTitle(title).orElseThrow().getProtectedPage());

        mockMvc.perform(post("/api/pages/protect").session(adminSession)
                        .param("title", title).param("protected", "false"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已解除编辑保护"));
        assertFalse(wikiPageService.getByTitle(title).orElseThrow().getProtectedPage());
    }

    @Test
    void moveApiMovesPageKeepingHistory() throws Exception {
        String from = "移动源页" + System.nanoTime();
        String occupied = "移动占用页" + System.nanoTime();
        String to = "移动目标页" + System.nanoTime();
        WikiUser user = insertUser("移动普通用户" + System.nanoTime());
        wikiPageService.editPage(user, from, "移动前内容");
        wikiPageService.editPage(user, from, "移动后内容", "再次编辑");
        wikiPageService.editPage(user, occupied, "占用");
        MockHttpSession session = sessionOf(user);

        // 目标已存在 → 409
        mockMvc.perform(post("/api/pages/move").session(session)
                        .param("from", from).param("to", occupied))
                .andExpect(jsonPath("$.code").value(409));

        // 正常移动 → 200
        mockMvc.perform(post("/api/pages/move").session(session)
                        .param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("页面已移动"))
                .andExpect(jsonPath("$.data.title").value(to));

        // 旧标题已不存在：访问是 404（自定义 404 页提供创建入口）；新标题内容在，历史保留 2 个版本
        mockMvc.perform(get("/wiki/{title}", from))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", to))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("移动后内容")));
        WikiPage moved = wikiPageService.getByTitle(to).orElseThrow();
        assertEquals(2, wikiPageService.getHistory(moved.getId()).size());

        // 移动日志
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("type", "move"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("移动了页面")))
                .andExpect(content().string(containsString(to)));
    }

    @Test
    void movingProtectedPageRequiresAdmin() throws Exception {
        String from = "受保护移动页" + System.nanoTime();
        String to = "受保护移动目标" + System.nanoTime();
        insertPage(from, "内容", true);

        WikiUser normal = insertUser("移动受限用户" + System.nanoTime());
        mockMvc.perform(post("/api/pages/move").session(sessionOf(normal))
                        .param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/pages/move").session(sessionOf(adminUser()))
                        .param("from", from).param("to", to))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void moveApiRejectsInvalidTargetTitle() throws Exception {
        String from = "移动非法目标源" + System.nanoTime();
        WikiUser user = insertUser("移动非法目标用户" + System.nanoTime());
        wikiPageService.editPage(user, from, "内容");

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", "非法|目标" + System.nanoTime()))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("非法字符")));
        assertTrue(wikiPageService.getByTitle(from).isPresent(), "标题非法时页面不应被移动");
    }

    @Test
    void moveAndProtectFormsRender() throws Exception {
        String title = "表单测试页" + System.nanoTime();
        insertPage(title, "内容", false);
        MockHttpSession session = sessionOf(adminUser());
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
        String backLink = "class=\"wiki-button\" href=\"/wiki/" + encoded + "\">返回</a>";

        String moveHtml = mockMvc.perform(get("/wiki/{title}", "特殊:移动页面").param("page", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-move-form\"")))
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString(backLink)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 有权限时：返回按钮在「移动页面」提交按钮右边
        assertTrue(moveHtml.indexOf(">移动页面</button>") >= 0
                        && moveHtml.indexOf(">返回</a>") > moveHtml.indexOf(">移动页面</button>"),
                "返回按钮应显示在提交按钮右边");

        String protectHtml = mockMvc.perform(get("/wiki/{title}", "特殊:保护页面").param("page", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-protect-form\"")))
                .andExpect(content().string(containsString("仅管理员可编辑")))
                .andExpect(content().string(containsString(backLink)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(protectHtml.indexOf(">确认</button>") >= 0
                        && protectHtml.indexOf(">返回</a>") > protectHtml.indexOf(">确认</button>"),
                "返回按钮应显示在「确认」按钮右边");

        // 没有 ?page=（标题查找界面）时没有可返回的页面，不显示返回按钮
        mockMvc.perform(get("/wiki/{title}", "特殊:移动页面").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(">返回</a>"))));
    }

    @Test
    void backButtonVisibleWhenFormIsNotRendered() throws Exception {
        String title = "无权限返回页" + System.nanoTime();
        insertPage(title, "内容", false);
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
        String backLink = "class=\"wiki-button\" href=\"/wiki/" + encoded + "\">返回</a>";

        // 匿名（未登录）：没有表单，但返回按钮必须还在
        mockMvc.perform(get("/wiki/{title}", "特殊:移动页面").param("page", title))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"wiki-move-form\""))))
                .andExpect(content().string(containsString(backLink)));
        mockMvc.perform(get("/wiki/{title}", "特殊:保护页面").param("page", title))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"wiki-protect-form\""))))
                .andExpect(content().string(containsString(backLink)));

        // 登录但没有权限（普通用户）：保护页面默认只有管理员能改 → 没有表单，返回按钮仍在
        MockHttpSession session = sessionOf(insertUser("无权限用户" + System.nanoTime()));
        mockMvc.perform(get("/wiki/{title}", "特殊:保护页面").param("page", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"wiki-protect-form\""))))
                .andExpect(content().string(containsString(backLink)));

        // 受保护页面 + 非管理员：不能移动，但返回按钮仍在
        String protectedTitle = "无权限受保护页" + System.nanoTime();
        insertPage(protectedTitle, "内容", true);
        String protectedEncoded = URLEncoder.encode(protectedTitle, StandardCharsets.UTF_8).replace("+", "%20");
        mockMvc.perform(get("/wiki/{title}", "特殊:移动页面").param("page", protectedTitle).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"wiki-move-form\""))))
                .andExpect(content().string(containsString(
                        "class=\"wiki-button\" href=\"/wiki/" + protectedEncoded + "\">返回</a>")));
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
        WikiUser user = insertUser("保护管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private void insertPage(String title, String content, boolean protectedPage) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(false);
        page.setDeleted(false);
        page.setProtectedPage(protectedPage);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setDeleted(false);
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }
}
