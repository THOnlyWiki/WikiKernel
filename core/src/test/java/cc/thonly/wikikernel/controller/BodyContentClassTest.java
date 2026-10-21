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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 所有页面统一的内容容器：{@code #bodyContent.wiki-content}——
 * 词条阅读页、特殊页、编辑页的正文排版（标题衬线体 / 分隔线等）都走同一套 .wiki-content 样式。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class BodyContentClassTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void everyPageUsesSameBodyContentClass() throws Exception {
        String title = "统一正文容器页" + System.nanoTime();
        wikiPageService.editPage(null, title, "统一容器正文");
        WikiUser user = insertUser("统一容器用户" + System.nanoTime());

        // 词条阅读页
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bodyContent\" class=\"wiki-content\"")))
                .andExpect(content().string(containsString("统一容器正文")))
                .andExpect(content().string(containsString("<section>")));

        // 特殊页（以前没有 .wiki-content 包装的地方）
        mockMvc.perform(get("/wiki/{title}", "特殊:特殊页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bodyContent\" class=\"wiki-content\"")))
                .andExpect(content().string(containsString("class=\"mw-specialpagesgroup\"")));

        // 编辑页
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bodyContent\" class=\"wiki-content\"")))
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
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
