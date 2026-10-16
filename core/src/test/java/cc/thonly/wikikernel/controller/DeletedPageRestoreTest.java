package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.LogService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已删除页面（仿 MediaWiki）：访问被删页面时的「此页面已被删除」提示 + 找回源代码、
 * {@code 特殊:已删除页面}（Special:Undelete）的列表 / 版本原文 / 恢复、{@code POST /api/pages/restore}
 * 的权限与日志、以及「重新创建 = 复活同一个页面」。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class DeletedPageRestoreTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void deletedPageShowsNoticeAndSourceForAdmins() throws Exception {
        WikiUser admin = admin("删除提示管理员");
        WikiUser editor = insertUser("删除提示编辑" + System.nanoTime());
        String title = "删除提示页" + System.nanoTime();
        wikiPageService.editPage(editor, title, "被删页正文：源代码内容 ABC");
        wikiPageService.deletePage(admin, title);

        // 管理员：提示 + 「找回源代码」（最近版本原文可见）
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("此页面已被删除")))
                .andExpect(content().string(containsString("查看或恢复已删除的编辑")))
                .andExpect(content().string(containsString("找回源代码")))
                .andExpect(content().string(containsString("wiki-deleted-source")))
                .andExpect(content().string(containsString("源代码内容 ABC")));

        // 普通用户：只有提示，看不到被删内容（没有源代码区块）
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(editor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("此页面已被删除")))
                .andExpect(content().string(containsString("只有管理员可以查看或恢复")))
                .andExpect(content().string(not(containsString("wiki-deleted-source"))))
                .andExpect(content().string(not(containsString("源代码内容 ABC"))));

        // 匿名：同样只有提示（不再是 404）
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("此页面已被删除")))
                .andExpect(content().string(not(containsString("wiki-deleted-source"))))
                .andExpect(content().string(not(containsString("源代码内容 ABC"))));

        // 历史页（?action=history）也不 404，同样给提示
        mockMvc.perform(get("/wiki/{title}", title).param("action", "history").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("此页面已被删除")));
    }

    @Test
    void restoreApiRestoresPageAndWritesLog() throws Exception {
        WikiUser admin = admin("恢复管理员");
        WikiUser editor = insertUser("恢复编辑" + System.nanoTime());
        String title = "待恢复页" + System.nanoTime();
        wikiPageService.editPage(editor, title, "恢复前的内容");

        // 没有删除过：404
        mockMvc.perform(post("/api/pages/restore").session(sessionOf(admin)).param("title", title))
                .andExpect(jsonPath("$.code").value(404));

        wikiPageService.deletePage(admin, title);

        // 普通用户：403
        mockMvc.perform(post("/api/pages/restore").session(sessionOf(editor)).param("title", title))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员：恢复成功，页面回到正常视图
        mockMvc.perform(post("/api/pages/restore").session(sessionOf(admin)).param("title", title))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("恢复前的内容")))
                .andExpect(content().string(not(containsString("此页面已被删除"))));

        // 写入了 restore 日志
        assertTrue(logService.recent(null, admin.getId(), true, 50).stream()
                        .anyMatch(log -> "restore".equals(log.getLogType()) && title.equals(log.getTarget())),
                "应写入 restore 日志");
    }

    @Test
    void reCreatingADeletedPageRevivesIt() {
        WikiUser admin = admin("重建管理员");
        WikiUser editor = insertUser("重建编辑" + System.nanoTime());
        String title = "重建页" + System.nanoTime();
        wikiPageService.editPage(editor, title, "旧内容");
        wikiPageService.deletePage(admin, title);

        // 删除后仍然可以编辑（页面模式 = 创建），保存不会撞标题唯一键，原页面复活
        WikiPage revived = wikiPageService.editPage(editor, title, "重新创建的新内容");
        assertNotNull(revived);
        assertTrue(!Boolean.TRUE.equals(revived.getDeleted()), "重新创建后页面应为未删除状态");
        assertTrue(wikiPageService.getByTitle(title).isPresent(), "重新创建后应能按标题查到页面");
        assertTrue(wikiPageService.getLatestContent(revived.getId()).orElse("").contains("重新创建的新内容"));
    }

    @Test
    void deletedPagesSpecialPageListsDetailsAndGuardsAdmins() throws Exception {
        WikiUser admin = admin("已删列表管理员");
        WikiUser editor = insertUser("已删列表编辑" + System.nanoTime());
        String title = "已删列表页" + System.nanoTime();
        wikiPageService.editPage(editor, title, "列表页被删的正文");
        wikiPageService.deletePage(admin, title);

        // 列表（管理员）
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除页面").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("恢复")));

        // 详情：被删版本的原文
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除页面").param("page", title).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("被删除的版本")))
                .andExpect(content().string(containsString("列表页被删的正文")))
                .andExpect(content().string(containsString("wiki-restore-button")));

        // 英文别名
        mockMvc.perform(get("/wiki/{title}", "Special:Undelete").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已删除页面")));

        // 普通用户：只看到权限提示，看不到被删页面标题
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除页面").session(sessionOf(editor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("需要管理员")))
                .andExpect(content().string(not(containsString(title))));

        // 恢复后列表里不再有它
        wikiPageService.restorePage(admin, title);
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除页面").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(title))));
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
