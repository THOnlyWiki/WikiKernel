package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
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
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 条目重定向：{@code #REDIRECT [[目标]]} / {@code #重定向}（大小写不敏感、支持链、防循环），
 * {@code ?redirect=no} 查看重定向页本身，目标页显示「重定向自 X」；
 * 移动页面可用 {@code leaveRedirect=1} 在旧标题留下 {@code #REDIRECT}。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WikiRedirectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void redirectFollowsTargetWithNoteAndRedirectNo() throws Exception {
        WikiUser user = insertUser("重定向用户" + System.nanoTime());
        String target = "重定向目标" + System.nanoTime();
        String source = "重定向来源" + System.nanoTime();
        wikiPageService.editPage(user, target, "目标正文");
        wikiPageService.editPage(user, source, "#重定向 [[" + target + "]]");

        // 访问重定向页 → 302 到目标，并带 redirectfrom（供目标页提示来源）
        MvcResult result = mockMvc.perform(get("/wiki/{title}", source))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertTrue(decoded.endsWith("?redirectfrom=" + source), decoded);
        assertTrue(decoded.startsWith("/wiki/" + target), decoded);

        // 目标页显示「重定向自 X」，链接带 ?redirect=no 可看重定向页
        mockMvc.perform(get("/wiki/{title}", target).queryParam("redirectfrom", source))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("目标正文")))
                .andExpect(content().string(containsString("重定向自")))
                .andExpect(content().string(containsString(source)))
                .andExpect(content().string(containsString("redirect=no")));

        // ?redirect=no：不跳转，按普通页面看（内容里能看到目标链接）
        mockMvc.perform(get("/wiki/{title}", source).queryParam("redirect", "no"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REDIRECT")))
                .andExpect(content().string(containsString(target)));
    }

    @Test
    void redirectChainResolvesAndLoopsRenderPageInsteadOfLooping() throws Exception {
        WikiUser user = insertUser("重定向链用户" + System.nanoTime());
        String c = "链目标C" + System.nanoTime();
        String b = "链中间B" + System.nanoTime();
        String a = "链起点A" + System.nanoTime();
        wikiPageService.editPage(user, c, "C 的正文");
        wikiPageService.editPage(user, b, "#REDIRECT [[" + c + "]]");
        wikiPageService.editPage(user, a, "#redirect [[" + b + "]]");

        // 链式解析：A → C（一次跳到底）
        MvcResult chained = mockMvc.perform(get("/wiki/{title}", a)).andReturn();
        String decoded = URLDecoder.decode(chained.getResponse().getHeader("Location"), StandardCharsets.UTF_8);
        assertEquals(302, chained.getResponse().getStatus());
        assertTrue(decoded.startsWith("/wiki/" + c), decoded);
        assertTrue(decoded.endsWith("?redirectfrom=" + a), decoded);

        // 目标不存在：仍然跳到目标（浏览器落点会进入创建页）
        String missing = "不存在目标" + System.nanoTime();
        String toMissing = "跳向缺失" + System.nanoTime();
        wikiPageService.editPage(user, toMissing, "#REDIRECT [[" + missing + "]]");
        MvcResult absent = mockMvc.perform(get("/wiki/{title}", toMissing)).andReturn();
        String absentLocation = URLDecoder.decode(absent.getResponse().getHeader("Location"), StandardCharsets.UTF_8);
        assertEquals(302, absent.getResponse().getStatus());
        assertTrue(absentLocation.startsWith("/wiki/" + missing), absentLocation);

        // 循环重定向：A ↔ B 时不再 302（渲染页面，避免死循环）
        String x = "循环X" + System.nanoTime();
        String y = "循环Y" + System.nanoTime();
        wikiPageService.editPage(user, x, "#REDIRECT [[" + y + "]]");
        wikiPageService.editPage(user, y, "#REDIRECT [[" + x + "]]");
        mockMvc.perform(get("/wiki/{title}", x))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REDIRECT")));
    }

    @Test
    void moveCanLeaveRedirectBehind() throws Exception {
        WikiUser user = insertUser("移动重定向用户" + System.nanoTime());
        String from = "移动来源" + System.nanoTime();
        String to = "移动目标" + System.nanoTime();
        wikiPageService.editPage(user, from, "第一版");
        wikiPageService.editPage(user, from, "第二版");
        WikiPage original = wikiPageService.getByTitle(from).orElseThrow();

        mockMvc.perform(post("/api/pages/move").session(sessionOf(user))
                        .param("from", from).param("to", to).param("leaveRedirect", "1"))
                .andExpect(jsonPath("$.code").value(200));

        // 旧标题留下 #REDIRECT，访问时跳新标题
        WikiPage redirect = wikiPageService.getByTitle(from).orElseThrow();
        assertEquals("#REDIRECT [[" + to + "]]",
                wikiPageService.getLatestContent(redirect.getId()).orElseThrow());
        assertNotEquals(original.getId(), redirect.getId());
        MvcResult result = mockMvc.perform(get("/wiki/{title}", from)).andReturn();
        String decoded = URLDecoder.decode(result.getResponse().getHeader("Location"), StandardCharsets.UTF_8);
        assertEquals(302, result.getResponse().getStatus());
        assertTrue(decoded.startsWith("/wiki/" + to), decoded);

        // 新标题保留原页面 ID 与全部历史；内容为最新版
        WikiPage moved = wikiPageService.getByTitle(to).orElseThrow();
        assertEquals(original.getId(), moved.getId());
        assertTrue(wikiPageService.getHistory(moved.getId()).size() >= 2);
        mockMvc.perform(get("/wiki/{title}", to))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("第二版")));
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
