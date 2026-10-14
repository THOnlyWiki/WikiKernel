package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编辑辅助工具栏（仿 MediaWiki WikiEditor）：只出现在 Wikitext 编辑页（含段落编辑），
 * 代码页（.js / .css / 微件）用 CodeMirror、不加载工具栏；无编辑权限的查看源代码页也没有。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class EditToolbarTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void toolbarShowsOnWikitextEditPagesOnly() throws Exception {
        WikiUser user = insertUser("工具栏用户" + System.nanoTime());
        String title = "工具栏页" + System.nanoTime();
        wikiPageService.editPage(null, title, "== 甲 ==\n\n正文\n");

        // 普通 Wikitext 编辑页：工具栏 + 按钮 + 脚本 + 本地化示例文案
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-editor-toolbar\"")))
                .andExpect(content().string(containsString("data-action=\"bold\"")))
                .andExpect(content().string(containsString("data-action=\"italic\"")))
                .andExpect(content().string(containsString("data-action=\"link\"")))
                .andExpect(content().string(containsString("data-action=\"file\"")))
                .andExpect(content().string(containsString("data-action=\"ref\"")))
                .andExpect(content().string(containsString("data-action=\"signature\"")))
                .andExpect(content().string(containsString("data-action=\"table\"")))
                .andExpect(content().string(containsString("data-action=\"special-menu\"")))
                .andExpect(content().string(containsString("data-heading=\"2\"")))
                .andExpect(content().string(containsString("/js/edit-toolbar.js")))
                .andExpect(content().string(containsString("toolbarBoldSample")));

        // 段落编辑同样有工具栏
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").param("section", "1")
                        .session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-editor-toolbar\"")));

        // 没有编辑权限（匿名 → 查看源代码）：没有工具栏
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("wiki-editor-toolbar"))));

        // JS 代码页走 CodeMirror，没有工具栏
        String codeTitle = "工具栏脚本" + System.nanoTime() + ".js";
        wikiPageService.editPage(null, codeTitle, "let a = 1;");
        mockMvc.perform(get("/wiki/{title}", codeTitle).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"javascript\"")))
                .andExpect(content().string(not(containsString("wiki-editor-toolbar"))))
                .andExpect(content().string(not(containsString("/js/edit-toolbar.js"))));
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
