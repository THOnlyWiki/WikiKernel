package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.RateLimitProperties;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求速率限制（防字典爆破）：
 * <ul>
 *     <li>登录接口：同一「IP + 用户名」失败满 {@code max-attempts} 次后锁定，返回 429（即使之后密码正确也被拦）；</li>
 *     <li>登录成功会清除该键的失败记录；不同用户名互不影响；</li>
 *     <li>{@code /init} 密钥：错误的 {@code ?key=} 达到上限后页面显示「尝试次数过多」，
 *     {@code POST /api/setup/unlock} 同样返回 429。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Test
    void loginIsRateLimitedAfterRepeatedFailuresAndSuccessClears() throws Exception {
        WikiUser user = insertUser("限流登录用户" + System.nanoTime());
        String password = "correct-password";

        // 先失败 3 次，再用正确密码成功一次 → 计数被清除
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/user/login")
                            .param("username", user.getUsername())
                            .param("password", "wrong-" + i))
                    .andExpect(jsonPath("$.code").value(500));
        }
        mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername())
                        .param("password", password))
                .andExpect(jsonPath("$.code").value(200));

        // 清零后再失败 4 次仍不锁（< 5）
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/user/login")
                            .param("username", user.getUsername())
                            .param("password", "wrong-" + i))
                    .andExpect(jsonPath("$.code").value(500));
        }
        // 第 5 次失败触发锁定
        mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername())
                        .param("password", "wrong-last"))
                .andExpect(jsonPath("$.code").value(500));
        // 锁定后：正确密码也返回 429 + 剩余秒数
        mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername())
                        .param("password", password))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(containsString("秒后再试")));

        // 另一个用户名不受影响（键 = IP + 用户名）
        WikiUser other = insertUser("限流其他用户" + System.nanoTime());
        mockMvc.perform(post("/api/user/login")
                        .param("username", other.getUsername())
                        .param("password", "wrong"))
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void setupKeyIsRateLimited() throws Exception {
        int max = rateLimitProperties.getSetup().getMaxAttempts();
        for (int i = 0; i < max; i++) {
            mockMvc.perform(post("/api/setup/unlock").param("key", "wrong-key-" + i))
                    .andExpect(jsonPath("$.code").value(403));
        }
        // API：锁定后返回 429
        mockMvc.perform(post("/api/setup/unlock").param("key", "anything"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(containsString("秒后再试")));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(cc.thonly.wikikernel.user.MediaWikiPassword.hash("correct-password"));
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
