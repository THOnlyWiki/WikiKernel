package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiLogMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 时区功能：
 * <ul>
 *     <li>注册时记录浏览器时区（非法值静默回退服务器时区）；</li>
 *     <li>参数设置可改时区（非法值 400）；</li>
 *     <li>历史 / 日志 / 最近更改的时间按登录用户时区显示（数据库仍按服务器时区存储）；</li>
 *     <li>未登录回退服务器时区。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class TimeZoneTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiLogMapper wikiLogMapper;

    @Autowired
    private MessageSource messageSource;

    @Test
    void registrationCapturesBrowserTimeZone() throws Exception {
        String name = "时区注册" + System.nanoTime();
        mockMvc.perform(post("/api/user/register")
                        .param("username", name).param("password", "secret123")
                        .param("timeZone", "Asia/Tokyo"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("Asia/Tokyo", userService.findByUsername(name).orElseThrow().getTimeZone());

        // 非法时区不阻断注册：忽略并跟随服务器时区
        String invalid = name + "坏";
        mockMvc.perform(post("/api/user/register")
                        .param("username", invalid).param("password", "secret123")
                        .param("timeZone", "Not/A_Zone"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(userService.findByUsername(invalid).orElseThrow().getTimeZone() == null
                || userService.findByUsername(invalid).orElseThrow().getTimeZone().isEmpty());
    }

    @Test
    void preferencesValidateAndSaveTimeZone() throws Exception {
        WikiUser user = userService.createUser("时区参数用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        mockMvc.perform(post("/api/user/preferences").session(session).param("timeZone", "Europe/Paris"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("Europe/Paris", userService.findById(user.getId()).orElseThrow().getTimeZone());

        mockMvc.perform(post("/api/user/preferences").session(session).param("timeZone", "Bad/Zone"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("无效的时区。"));

        // 空 = 跟随服务器时区
        mockMvc.perform(post("/api/user/preferences").session(session).param("timeZone", ""))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(userService.findById(user.getId()).orElseThrow().getTimeZone().isEmpty());
    }

    @Test
    void preferencesPageShowsTimeZoneOptions() throws Exception {
        WikiUser user = userService.createUser("时区页面用户" + System.nanoTime(), "secret123", "");
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"pref-timezone\"")))
                .andExpect(content().string(containsString("pref-timezone-detect")))
                .andExpect(content().string(containsString("Asia/Shanghai")));
    }

    @Test
    void historyLogsAndRecentChangesFollowUserTimeZone() throws Exception {
        WikiUser user = userService.createUser("时区显示用户" + System.nanoTime(), "secret123", "");
        String page = "时区条目" + System.nanoTime();
        WikiPage wikiPage = wikiPageService.editPage(user, page, "内容");
        LocalDateTime revisionTime = wikiPageService.getLatestRevision(wikiPage.getId()).orElseThrow().getCreateAt();

        String pattern = messageSource.getMessage("format.dateTime", null, Locale.SIMPLIFIED_CHINESE);
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        String expectedHistory = DateTimeFormatter.ofPattern(pattern)
                .format(revisionTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(tokyo));

        MockHttpSession session = sessionOf(user);
        mockMvc.perform(post("/api/user/preferences").session(session).param("timeZone", "Asia/Tokyo"))
                .andExpect(jsonPath("$.code").value(200));

        // 版本历史按用户时区显示
        mockMvc.perform(get("/wiki/{title}", page).param("action", "history").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedHistory)));

        // 最近更改的时间（HH:mm）也按用户时区
        String expectedRcTime = DateTimeFormatter.ofPattern("HH:mm")
                .format(revisionTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(tokyo));
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedRcTime)));

        // 日志页：插入一条固定时间的日志，验证按用户时区换算
        LocalDateTime logTime = LocalDateTime.now().minusHours(1);
        WikiLog log = new WikiLog();
        log.setLogType(LogTypeConfig.REGISTER);
        log.setActorUserId(user.getId());
        log.setTargetUserId(user.getId());
        log.setHided(false);
        log.setTime(logTime);
        wikiLogMapper.insert(log);

        String expectedLog = DateTimeFormatter.ofPattern(pattern)
                .format(logTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(tokyo));
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("type", "register").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedLog)));

        // 未登录回退服务器时区
        String expectedServer = DateTimeFormatter.ofPattern(pattern).format(logTime);
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("type", "register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedServer)));
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
