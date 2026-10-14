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
 * 评论（{@code <comment />}）已迁到插件 {@code comments}：**core 未加载插件时**
 * 标签按原文转义显示、页面底部没有评论区、评论 API 不存在；插件加载后由
 * {@code ParserTag}（剥离标签 + 写页面属性）与 {@code PageDisplayHook.afterContent}（渲染评论区）接管。
 * <p>
 * 插件的完整行为（发表 / 回复 / 缩进 / 软删除 / 头像 / 权限）见
 * {@code plugin/comments/src/test}（{@code CommentSectionTest}）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class CommentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Test
    void commentTagIsUnknownToCoreWithoutPlugin() throws Exception {
        WikiUser author = insertUser("评论页作者" + System.nanoTime());
        String page = "评论页" + System.nanoTime();
        wikiPageService.editPage(author, page, "前文\n\n<comment />\n\n后文");

        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                // 插件未加载：标签不是 core 的已知标签，按原文转义显示
                .andExpect(content().string(containsString("&lt;comment /&gt;")))
                // 没有评论区、没有评论脚本 / 样式（都由插件注入）
                .andExpect(content().string(not(containsString("wiki-comments"))))
                .andExpect(content().string(not(containsString("/plugins/comments/"))))
                // 页面其余内容照常渲染
                .andExpect(content().string(containsString("前文")))
                .andExpect(content().string(containsString("后文")));
    }

    @Test
    void commentApiIsProvidedByPluginOnly() throws Exception {
        // 评论 API 由插件提供：core 未加载插件时该路径不存在（404 / 405）
        mockMvc.perform(get("/api/comments"))
                .andExpect(status().is4xxClientError());
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
