package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WatchService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监视列表（仿 MediaWiki）：页签「监视 / 取消监视」、监视列表页、编辑监视列表、权限。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WatchlistTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Autowired
    private WatchService watchService;

    @Test
    void watchToggleAndWatchlistPage() throws Exception {
        WikiUser user = userService.createUser("监视用户" + System.nanoTime(), "secret123", "");
        String title = "监视测试页" + System.nanoTime();
        wikiPageService.editPage(user, title, "监视内容");
        MockHttpSession session = sessionOf(user);

        // 登录内容页：有「监视」页签且未监视
        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"watch-toggle\"")))
                .andExpect(content().string(containsString("data-watched=\"false\"")));

        // 匿名：没有开关（显示登录链接）
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"watch-toggle\""))));

        // 加入监视
        mockMvc.perform(post("/api/watch").session(session)
                        .param("title", title).param("action", "watch"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.watched").value(true));
        assertTrue(watchService.isWatched(user.getId(), title));

        // 页签变为「取消监视」
        mockMvc.perform(get("/wiki/{title}", title).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-watched=\"true\"")))
                .andExpect(content().string(containsString("取消监视")));

        // 监视列表页：列出页面与那笔编辑（含差异链接）
        mockMvc.perform(get("/wiki/{title}", "特殊:监视列表").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("差异")))
                .andExpect(content().string(containsString("监视的页面")));

        // 移除
        mockMvc.perform(post("/api/watch").session(session)
                        .param("title", title).param("action", "unwatch"))
                .andExpect(jsonPath("$.data.watched").value(false));
        assertFalse(watchService.isWatched(user.getId(), title));
    }

    @Test
    void rawWatchlistEditorReplacesList() throws Exception {
        WikiUser user = userService.createUser("监视编辑用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);
        String first = "监视甲" + System.nanoTime();
        String second = "监视乙" + System.nanoTime();

        mockMvc.perform(get("/wiki/{title}", "特殊:编辑监视列表").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("watchlist-raw-form")));

        mockMvc.perform(post("/api/watch/raw").session(session)
                        .param("watchlist", first + "\n" + second))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.count").value(2));
        assertTrue(watchService.titles(user.getId()).containsAll(List.of(first, second)));

        // 整体替换：只留一个
        mockMvc.perform(post("/api/watch/raw").session(session).param("watchlist", second))
                .andExpect(jsonPath("$.data.count").value(1));
        List<String> titles = watchService.titles(user.getId());
        assertTrue(titles.contains(second));
        assertFalse(titles.contains(first));

        // 匿名：401 / 页面提示登录
        mockMvc.perform(post("/api/watch").param("title", second))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/wiki/{title}", "特殊:监视列表"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请先登录")));
    }

    private static MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
