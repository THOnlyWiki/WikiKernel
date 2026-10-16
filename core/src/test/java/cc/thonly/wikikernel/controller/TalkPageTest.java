package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.web.WikiUrls;

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
 * 讨论页签：普通页面 → 讨论命名空间（可点击），讨论页 → 链回主题页并选中「讨论」；
 * 特殊页不显示页面/讨论页签。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class TalkPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void pageTalkTabLinksToTalkNamespaceAndTalkPageLinksBack() throws Exception {
        WikiUser user = insertUser("讨论页用户" + System.nanoTime());
        String page = "讨论测试页" + System.nanoTime();
        String talk = "讨论:" + page;
        wikiPageService.editPage(user, page, "页面正文");
        wikiPageService.editPage(user, talk, "讨论页正文");

        // 普通页面：讨论页签是可点击的链接（不再是灰色禁用）
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(WikiUrls.pagePath(talk))))
                .andExpect(content().string(not(containsString(
                        "disabled\"><span title=\"尚未实现\">讨论</span>"))));

        // 讨论页：链回主题页，且「讨论」页签选中（自身页签为 span）
        mockMvc.perform(get("/wiki/{title}", talk))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("讨论页正文")))
                .andExpect(content().string(containsString(WikiUrls.pagePath(page))))
                .andExpect(content().string(containsString(">讨论</span>")));

        // 讨论页是普通页面：登录可编辑（编辑页签存在）
        mockMvc.perform(get("/wiki/{title}", talk).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));
    }

    @Test
    void userAndCategoryPagesUseTheirTalkNamespaces() throws Exception {
        WikiUser user = insertUser("讨论命名空间用户" + System.nanoTime());
        String userPage = "用户:" + user.getUsername();
        wikiPageService.editPage(user, userPage, "用户页正文");
        mockMvc.perform(get("/wiki/{title}", userPage))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        WikiUrls.pagePath("用户讨论:" + user.getUsername()))));

        String category = "讨论分类" + System.nanoTime();
        wikiPageService.editPage(user, "分类:" + category, "分类说明");
        mockMvc.perform(get("/wiki/{title}", "分类:" + category))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        WikiUrls.pagePath("分类讨论:" + category))));
    }

    @Test
    void specialPagesShowSpecialPageTab() throws Exception {
        // 特殊页显示「特殊页面」页签（仿 MediaWiki 的 ca-nstab-special），且没有页面 / 讨论页签
        mockMvc.perform(get("/wiki/{title}", "特殊:版本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<li class=\"selected\"><span>特殊页面</span></li>")))
                .andExpect(content().string(not(containsString(">讨论</a>"))));
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
