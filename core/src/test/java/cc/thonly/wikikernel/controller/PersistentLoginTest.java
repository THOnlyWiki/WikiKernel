package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiLoginToken;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiLoginTokenMapper;
import cc.thonly.wikikernel.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 持久登录：记住我签发独立 Token（存数据库），没有 Session（等价于服务重启）也能自动登录；
 * 退出登录会撤销令牌；不勾选记住我不发令牌；无效令牌不登录并清除 Cookie。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PersistentLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WikiLoginTokenMapper loginTokenMapper;

    @Test
    void rememberMeTokenSurvivesMissingSessionAndLogoutRevokesIt() throws Exception {
        WikiUser user = userService.createUser("持久登录用户" + System.nanoTime(), "secret123", "");

        // 登录 + 记住我 → 设置 wiki_token（HttpOnly）
        MvcResult login = mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername())
                        .param("password", "secret123")
                        .param("remember", "1"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("wiki_token");
        assertNotNull(cookie, "记住我登录应签发 wiki_token");
        assertTrue(cookie.isHttpOnly());

        // 数据库只存 selector + 哈希，不存明文 validator
        WikiLoginToken token = loginTokenMapper.selectOne(Wrappers.<WikiLoginToken>lambdaQuery()
                .eq(WikiLoginToken::getUserId, user.getId()));
        assertNotNull(token);
        assertEquals(cookie.getValue().split(":")[0], token.getSelector());
        assertTrue(cookie.getValue().indexOf(':') > 0);
        assertTrue(!token.getValidatorHash().contains(cookie.getValue().split(":")[1]));
        assertNotNull(token.getExpiresAt(), "默认应设置有限有效期（30 天）");
        assertTrue(token.getExpiresAt().isAfter(java.time.LocalDateTime.now()), "令牌尚未过期");

        // 只带 Token、没有 Session（等价于服务重启后第一次访问）→ 自动登录
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.getUsername())));

        // 退出登录：令牌被撤销、Cookie 清除
        mockMvc.perform(get("/api/user/logout").cookie(cookie))
                .andExpect(status().is3xxRedirection());
        assertEquals(0L, loginTokenMapper.selectCount(Wrappers.<WikiLoginToken>lambdaQuery()
                .eq(WikiLoginToken::getUserId, user.getId())));
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").cookie(cookie))
                .andExpect(content().string(not(containsString(user.getUsername()))));
    }

    @Test
    void loginWithoutRememberMeDoesNotIssueToken() throws Exception {
        WikiUser user = userService.createUser("不记住用户" + System.nanoTime(), "secret123", "");
        MvcResult login = mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername())
                        .param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        assertTrue(login.getResponse().getCookie("wiki_token") == null
                        || login.getResponse().getCookie("wiki_token").getValue().isEmpty(),
                "不勾选记住我不应签发令牌（最多下发清除用的空 Cookie）");
        assertEquals(0L, loginTokenMapper.selectCount(Wrappers.<WikiLoginToken>lambdaQuery()
                .eq(WikiLoginToken::getUserId, user.getId())));
    }

    @Test
    void invalidTokenIsRejectedAndCookieCleared() throws Exception {
        Cookie bogus = new Cookie("wiki_token", "deadbeef:deadbeef");
        MvcResult result = mockMvc.perform(get("/wiki/{title}", "特殊:版本").cookie(bogus))
                .andExpect(status().isOk())
                .andReturn();
        // 未登录：页面上没有「退出」链接；响应里带清除 Cookie 的 Set-Cookie
        assertTrue(!result.getResponse().getContentAsString().contains("退出"));
        assertTrue(result.getResponse().getHeaders("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith("wiki_token=") && header.contains("Max-Age=0")));
    }
}
