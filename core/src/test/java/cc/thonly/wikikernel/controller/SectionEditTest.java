package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrls;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 段落编辑（仿 MediaWiki）：标题旁 {@code [编辑]} 链接（只有可编辑者可见）、
 * {@code ?action=edit&section=N} 只编辑该段、保存时只替换该段（别人的改动保留）、
 * 同段并发修改给出冲突页、旧版本视图没有段落链接。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class SectionEditTest {

    private static final String PAGE = "导语\n\n== 甲 ==\n甲1\n\n== 乙 ==\n乙1\n\n== 丙 ==\n丙1\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void sectionLinksOnlyForEditorsAndSectionForm() throws Exception {
        String title = "段落页" + System.nanoTime();
        wikiPageService.editPage(null, title, PAGE);

        // 匿名（默认编辑权限 = 登录用户）：不显示段落编辑链接
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("甲1")))
                .andExpect(content().string(not(containsString("mw-editsection"))));

        WikiUser user = insertUser("段落用户" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"mw-editsection\"")))
                .andExpect(content().string(containsString("action=edit&amp;section=1")))
                .andExpect(content().string(containsString("action=edit&amp;section=2")))
                .andExpect(content().string(containsString("action=edit&amp;section=3")))
                .andExpect(content().string(containsString("编辑段落：乙")));

        // 编辑第 2 段：只有该段文本，摘要自动带 /* 乙 */，隐藏字段 section / baserevid 都在
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").param("section", "2")
                        .session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"section\"")))
                .andExpect(content().string(containsString("name=\"baserevid\"")))
                .andExpect(content().string(containsString("== 乙 ==")))
                .andExpect(content().string(containsString("乙1")))
                .andExpect(content().string(not(containsString("== 甲 =="))))
                .andExpect(content().string(not(containsString("甲1"))))
                .andExpect(content().string(containsString("/* 乙 */")))
                .andExpect(content().string(containsString("data-section=\"2\"")));
    }

    @Test
    void savingSectionPreservesOtherSectionsAndConcurrentChanges() throws Exception {
        WikiUser user = insertUser("段落保存用户" + System.nanoTime());
        String title = "段落保存页" + System.nanoTime();
        wikiPageService.editPage(null, title, PAGE);
        Long baseRevision = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();

        // 别人改了第 1 段；你基于旧版本提交第 2 段 → 应成功，两处修改都在
        wikiPageService.editPage(null, title, "导语\n\n== 甲 ==\n甲改\n\n== 乙 ==\n乙1\n\n== 丙 ==\n丙1\n");

        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user))
                        .param("content", "== 乙 ==\n乙新")
                        .param("comment", "改乙")
                        .param("section", "2")
                        .param("baserevid", String.valueOf(baseRevision)))
                .andExpect(status().is3xxRedirection())
                // 保存后跳回该段落锚点（#乙）
                .andExpect(redirectedUrl(WikiUrls.pagePath(title) + "#%E4%B9%99"));

        String content = latestContent(title);
        assertTrue(content.contains("甲改"), content);
        assertTrue(content.contains("乙新"), content);
        assertTrue(content.contains("丙1"), content);
    }

    @Test
    void sameSectionConcurrentChangeShowsConflict() throws Exception {
        WikiUser user = insertUser("段落冲突用户" + System.nanoTime());
        String title = "段落冲突页" + System.nanoTime();
        wikiPageService.editPage(null, title, PAGE);
        Long baseRevision = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();

        // 别人也改了第 2 段
        wikiPageService.editPage(null, title, "导语\n\n== 甲 ==\n甲1\n\n== 乙 ==\n乙服务端\n\n== 丙 ==\n丙1\n");

        // 你仍基于旧版本提交该段 → 冲突提示页，不落盘
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(user))
                        .param("content", "== 乙 ==\n乙客户端")
                        .param("section", "2")
                        .param("baserevid", String.valueOf(baseRevision)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑冲突")))
                .andExpect(content().string(containsString("乙客户端")))
                .andExpect(content().string(containsString("差异对照")))
                .andExpect(content().string(containsString("放弃我的修改")));

        String content = latestContent(title);
        assertTrue(content.contains("乙服务端"), content);
        assertFalse(content.contains("乙客户端"), content);
    }

    @Test
    void sectionLinksWorkForHeadingsWithRefs() throws Exception {
        String title = "段落引用页" + System.nanoTime();
        wikiPageService.editPage(null, title, "== 甲<ref>脚注A</ref> ==\n\nA\n\n== 乙 ==\n\nB\n\n<references/>");

        WikiUser user = insertUser("段落引用用户" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", title).session(sessionOf(user)))
                .andExpect(status().isOk())
                // 标题里的脚注照常渲染，且不污染锚点
                .andExpect(content().string(containsString("<h2 id=\"甲\">甲<sup class=\"reference\"")))
                .andExpect(content().string(containsString("cite_note-1")))
                // 含脚注的标题及其后的标题都还有段落编辑链接
                .andExpect(content().string(containsString("action=edit&amp;section=1")))
                .andExpect(content().string(containsString("action=edit&amp;section=2")))
                // 链接提示与编辑摘要里不出现 <ref> 源码
                .andExpect(content().string(containsString("编辑段落：甲")))
                .andExpect(content().string(not(containsString("编辑段落：甲<ref>"))));

        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").param("section", "1")
                        .session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/* 甲 */")))
                .andExpect(content().string(not(containsString("/* 甲<ref>"))));
    }

    @Test
    void oldRevisionViewHasNoSectionLinks() throws Exception {
        String title = "段落旧版页" + System.nanoTime();
        wikiPageService.editPage(null, title, PAGE);
        Long revisionId = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();
        wikiPageService.editPage(null, title, PAGE + "\n再改一次\n");

        WikiUser user = insertUser("段落旧版用户" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", title).param("oldid", String.valueOf(revisionId))
                        .session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("甲1")))
                .andExpect(content().string(not(containsString("mw-editsection"))));
    }

    // ==================== 工具 ====================

    private String latestContent(String title) {
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        return wikiPageService.getLatestContent(page.getId()).orElseThrow();
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
