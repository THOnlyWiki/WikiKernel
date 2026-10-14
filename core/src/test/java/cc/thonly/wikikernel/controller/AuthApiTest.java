package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录/注册页面与 {@code /api/user/*} 接口。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class AuthApiTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    private static String uniqueUsername() {
        return "测试用户" + System.nanoTime();
    }

    @Test
    void authPagesRender() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:登录"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-login-form\"")))
                .andExpect(content().string(containsString("记住我的登录状态")));

        mockMvc.perform(get("/wiki/{title}", "特殊:注册"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-register-form\"")))
                .andExpect(content().string(containsString("确认密码")));

        // MediaWiki 英文别名
        mockMvc.perform(get("/wiki/{title}", "Special:CreateAccount"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-register-form\"")));
    }

    @Test
    void registerCreatesUserAndMasksPassword() throws Exception {
        String username = uniqueUsername();

        mockMvc.perform(post("/api/user/register")
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("email", "test@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("注册成功"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.password").value(not(containsString("secret"))));
    }

    @Test
    void duplicateUsernameRejected() throws Exception {
        String username = uniqueUsername();
        register(username).andExpect(jsonPath("$.code").value(200));

        register(username)
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void blankFieldsRejected() throws Exception {
        mockMvc.perform(post("/api/user/register").param("username", "  ").param("password", PASSWORD))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/login").param("username", "x").param("password", " "))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void loginStoresSessionAndHeaderShowsUser() throws Exception {
        String username = uniqueUsername();
        register(username).andExpect(jsonPath("$.code").value(200));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/user/login")
                        .session(session)
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(jsonPath("$.code").value(200));
        assertNotNull(session.getAttribute(UserSession.ATTRIBUTE));

        mockMvc.perform(get("/wiki/{title}", "特殊:登录").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(username)))
                .andExpect(content().string(containsString(">退出</a>")));
    }

    @Test
    void wrongPasswordFailsAndLogoutInvalidatesSession() throws Exception {
        String username = uniqueUsername();
        register(username).andExpect(jsonPath("$.code").value(200));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/user/login")
                        .session(session)
                        .param("username", username)
                        .param("password", "wrong-password"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        mockMvc.perform(post("/api/user/login")
                        .session(session)
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/user/logout").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        assertTrue(session.isInvalid());
    }

    @Test
    void registerGrantsDefaultUserGroup() throws Exception {
        String username = uniqueUsername();
        register(username).andExpect(jsonPath("$.code").value(200));

        WikiUser user = wikiUserMapper.selectOne(Wrappers.<WikiUser>lambdaQuery()
                .eq(WikiUser::getUsername, username));
        assertNotNull(user, "注册后应能查到用户");
        assertTrue(permissionService.namesOf(user.getId()).contains(PermissionService.GROUP_USER));
    }

    private ResultActions register(String username) throws Exception {
        return mockMvc.perform(post("/api/user/register")
                .param("username", username)
                .param("password", PASSWORD));
    }
}
