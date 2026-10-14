package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.mail.EmailTypeConfig;
import cc.thonly.wikikernel.mail.MailService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱确认流程：发送确认邮件（MailService 被替换成 Mock，不真正连 SMTP）→
 * 打开确认链接 → 自动获得「邮箱确认用户」；令牌一次性、链接需对应用户、换绑邮箱会撤销确认。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.email.enabled=true",
        "wikikernel.email.host=smtp.example.com",
        "wikikernel.email.username=wiki@example.com",
        "wikikernel.email.from=wiki@example.com",
        "wikikernel.email.base-url=https://wiki.test"
})
@AutoConfigureMockMvc
class EmailConfirmationTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private MailProperties mailProperties;

    @MockitoBean
    private MailService mailService;

    @Test
    void confirmEmailGrantsEmailConfirmedGroup() throws Exception {
        WikiUser user = insertUser("邮箱确认甲" + System.nanoTime());
        MockHttpSession session = sessionOf(user);

        // 发送确认邮件：接口 200，邮件正文里有一次性链接
        mockMvc.perform(post("/api/user/email/confirm").session(session))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("确认邮件已发送，请查收（若没收到，请看看垃圾邮件）。"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EmailTypeConfig.CONFIRM_EMAIL), eq(user.getEmail()),
                anyString(), body.capture());
        String token = tokenOf(body.getValue());
        assertTrue(body.getValue().contains("https://wiki.test/email/confirm?token="));

        // 未登录打开链接：先跳登录页（带 returnto）
        mockMvc.perform(get("/email/confirm").param("token", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("returnto")));

        // 登录后打开：确认成功 + 自动获得「邮箱确认用户」
        mockMvc.perform(get("/email/confirm").param("token", token).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("电子邮件地址已确认")))
                .andExpect(content().string(containsString("邮箱确认用户")));
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_EMAIL_CONFIRMED));
        assertTrue(userService.findById(user.getId()).orElseThrow().getEmailConfirmed());
        assertTrue(permissionService.automaticGroupsOf(user.getId())
                .contains(PermissionService.GROUP_EMAIL_CONFIRMED));

        // 一次性令牌：再点一次视为无效
        mockMvc.perform(get("/email/confirm").param("token", token).session(session))
                .andExpect(content().string(containsString("确认链接无效")));
    }

    @Test
    void tokenBelongsToTheReceivingAccount() throws Exception {
        WikiUser owner = insertUser("邮箱确认乙" + System.nanoTime());
        WikiUser other = insertUser("邮箱确认丙" + System.nanoTime());

        mockMvc.perform(post("/api/user/email/confirm").session(sessionOf(owner)))
                .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EmailTypeConfig.CONFIRM_EMAIL), eq(owner.getEmail()),
                anyString(), body.capture());
        String token = tokenOf(body.getValue());

        // 其他用户打开：提示属于其他账号，权限不变
        mockMvc.perform(get("/email/confirm").param("token", token).session(sessionOf(other)))
                .andExpect(content().string(containsString("此确认链接属于其他账号")));
        assertFalse(permissionService.has(owner.getId(), PermissionService.GROUP_EMAIL_CONFIRMED));
        assertFalse(permissionService.has(other.getId(), PermissionService.GROUP_EMAIL_CONFIRMED));

        // 乱写的令牌：无效
        mockMvc.perform(get("/email/confirm").param("token", "bogus-token").session(sessionOf(owner)))
                .andExpect(content().string(containsString("确认链接无效")));
    }

    @Test
    void changingEmailClearsConfirmationAndGroup() throws Exception {
        WikiUser user = insertUser("邮箱确认丁" + System.nanoTime());
        MockHttpSession session = sessionOf(user);
        // 参数设置页按真实配置决定是否显示「发送确认邮件」按钮
        org.mockito.Mockito.when(mailService.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(mailService.isEnabled(EmailTypeConfig.CONFIRM_EMAIL)).thenReturn(true);

        mockMvc.perform(post("/api/user/email/confirm").session(session))
                .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EmailTypeConfig.CONFIRM_EMAIL), eq(user.getEmail()),
                anyString(), body.capture());
        mockMvc.perform(get("/email/confirm").param("token", tokenOf(body.getValue())).session(session))
                .andExpect(status().isOk());
        assertTrue(permissionService.has(user.getId(), PermissionService.GROUP_EMAIL_CONFIRMED));

        // 换绑邮箱：确认状态与「邮箱确认用户」一起撤销
        mockMvc.perform(post("/api/user/preferences").session(session)
                        .param("email", "换绑" + System.nanoTime() + "@example.com")
                        .param("rcLimit", "0")
                        .param("rcDays", "0")
                        .param("searchLimit", "0"))
                .andExpect(jsonPath("$.code").value(200));

        WikiUser updated = userService.findById(user.getId()).orElseThrow();
        assertFalse(updated.getEmailConfirmed());
        assertFalse(permissionService.has(user.getId(), PermissionService.GROUP_EMAIL_CONFIRMED));

        // 参数设置页也反映“未确认”
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(session))
                .andExpect(content().string(containsString("未确认")))
                .andExpect(content().string(containsString("发送确认邮件")))
                .andExpect(content().string(not(containsString("邮件发送功能尚未实现"))));
    }

    @Test
    void confirmationSendHasCooldown() throws Exception {
        WikiUser user = insertUser("冷却用户" + System.nanoTime());
        MockHttpSession session = sessionOf(user);

        // 第一次发送成功
        mockMvc.perform(post("/api/user/email/confirm").session(session))
                .andExpect(jsonPath("$.code").value(200));

        // 冷却期内再发：被拒绝，且不会真正再发邮件
        mockMvc.perform(post("/api/user/email/confirm").session(session))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(containsString("发送过于频繁")))
                .andExpect(jsonPath("$.message").value(containsString("秒后重试")));
        verify(mailService, times(1)).send(eq(EmailTypeConfig.CONFIRM_EMAIL),
                anyString(), anyString(), anyString());

        // 冷却配置为 0 = 不限制
        mailProperties.setConfirmCooldownSeconds(0);
        try {
            mockMvc.perform(post("/api/user/email/confirm").session(session))
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            mailProperties.setConfirmCooldownSeconds(60);
        }
    }

    // ==================== 工具 ====================

    private String tokenOf(String emailBody) {
        Matcher matcher = TOKEN.matcher(emailBody);
        assertTrue(matcher.find(), "邮件正文里应该有确认链接：" + emailBody);
        return matcher.group(1);
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail(username + "@example.com");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
