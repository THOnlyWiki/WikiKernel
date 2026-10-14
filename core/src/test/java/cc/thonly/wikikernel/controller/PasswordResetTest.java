package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.mail.EmailTypeConfig;
import cc.thonly.wikikernel.mail.MailService;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 忘记密码（仿 MediaWiki Special:PasswordReset）：
 * 用户名 / 邮箱申请 → 邮件里的重置链接 → 设置新密码（一次性令牌）；
 * 未知账号与成功提示一致（不暴露账号是否存在）；同一账号发送有冷却。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.email.enabled=true",
        "wikikernel.email.host=smtp.example.com",
        "wikikernel.email.username=wiki@example.com",
        "wikikernel.email.from=wiki@example.com",
        "wikikernel.email.base-url=https://wiki.test",
        "wikikernel.email.password-reset-cooldown-seconds=0"
})
@AutoConfigureMockMvc
class PasswordResetTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private MailProperties mailProperties;

    @MockitoBean
    private MailService mailService;

    @Test
    void requestByUsernameSendsLinkAndResetWorks() throws Exception {
        WikiUser user = insertUser("重置密码甲" + System.nanoTime());

        // 申请：页面提示「重置邮件已经发送」，邮件里有 /password/reset?token= 链接
        mockMvc.perform(post("/password/reset-request").with(csrf()).param("username", user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("重置邮件已经发送")));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EmailTypeConfig.PASSWORD_RESET), eq(user.getEmail()),
                anyString(), body.capture());
        String token = tokenOf(body.getValue());
        assertTrue(body.getValue().contains("https://wiki.test/password/reset?token="));

        // 打开链接：设置新密码表单
        mockMvc.perform(get("/password/reset").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("新密码")));

        // 两次密码不一致 → 错误；令牌不会因此作废
        mockMvc.perform(post("/password/reset").with(csrf()).param("token", token)
                        .param("password", "new-secret-1").param("confirmPassword", "new-secret-2"))
                .andExpect(content().string(containsString("两次输入的密码不一致")));

        // 保存成功
        mockMvc.perform(post("/password/reset").with(csrf()).param("token", token)
                        .param("password", "new-secret-1").param("confirmPassword", "new-secret-1"))
                .andExpect(content().string(containsString("密码已重置")));

        // 旧密码失效、新密码可登录（登录成功会清除限流计数）
        mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername()).param("password", "x-password"))
                .andExpect(jsonPath("$.code").value(500));
        mockMvc.perform(post("/api/user/login")
                        .param("username", user.getUsername()).param("password", "new-secret-1"))
                .andExpect(jsonPath("$.code").value(200));

        // 一次性令牌：链接再打开视为无效
        mockMvc.perform(get("/password/reset").param("token", token))
                .andExpect(content().string(containsString("重置链接无效")));
    }

    @Test
    void requestByEmailIsCaseInsensitiveAndUnknownAccountLooksTheSame() throws Exception {
        WikiUser user = insertUser("重置密码乙" + System.nanoTime());
        // 用大写邮箱申请（忽略大小写）
        mockMvc.perform(post("/password/reset-request").with(csrf()).param("username", user.getEmail().toUpperCase()))
                .andExpect(content().string(containsString("重置邮件已经发送")));
        verify(mailService).send(eq(EmailTypeConfig.PASSWORD_RESET), eq(user.getEmail()),
                anyString(), anyString());

        // 未知账号：提示一致，不发邮件
        mockMvc.perform(post("/password/reset-request").with(csrf()).param("username", "不存在账号" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("重置邮件已经发送")));
        verify(mailService, times(1)).send(eq(EmailTypeConfig.PASSWORD_RESET), anyString(),
                anyString(), anyString());
    }

    @Test
    void sendingHasCooldown() throws Exception {
        WikiUser user = insertUser("重置密码丙" + System.nanoTime());
        mailProperties.setPasswordResetCooldownSeconds(300);
        try {
            mockMvc.perform(post("/password/reset-request").with(csrf()).param("username", user.getUsername()))
                    .andExpect(content().string(containsString("重置邮件已经发送")));
            // 冷却期内再次申请：页面提示一致，但不会再发邮件
            mockMvc.perform(post("/password/reset-request").with(csrf()).param("username", user.getUsername()))
                    .andExpect(content().string(containsString("重置邮件已经发送")));
            verify(mailService, times(1)).send(eq(EmailTypeConfig.PASSWORD_RESET),
                    anyString(), anyString(), anyString());
        } finally {
            mailProperties.setPasswordResetCooldownSeconds(0);
        }
    }

    // ==================== 工具 ====================

    private String tokenOf(String emailBody) {
        Matcher matcher = TOKEN.matcher(emailBody);
        assertTrue(matcher.find(), "邮件正文里应该有重置链接：" + emailBody);
        return matcher.group(1);
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(MediaWikiPassword.hash("x-password"));
        user.setEmail(username + "@example.com");
        user.setEmailConfirmed(true);
        wikiUserMapper.insert(user);
        return user;
    }
}
