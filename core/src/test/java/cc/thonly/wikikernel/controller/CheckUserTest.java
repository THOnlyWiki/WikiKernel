package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.web.WikiUrls;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiLogMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户核查（特殊:用户核查）：
 * 每条日志记录 IP / UA（开启 {@code wikikernel.http.trust-proxy-headers} 后从 CDN 头取真实 IP）；
 * 核查员可按用户 / 按 IP / 按 CIDR 查询，普通用户看不到内容。
 * <p>
 * 独立上下文：开启了 trust-proxy-headers。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.http.trust-proxy-headers=true"
})
@AutoConfigureMockMvc
class CheckUserTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiLogMapper wikiLogMapper;

    @Autowired
    private PermissionService permissionService;

    @Test
    void logsRecordRealIpFromCdnAndCheckUserCanQuery() throws Exception {
        WikiUser user = insertUser("核查目标" + System.nanoTime());
        WikiUser checkuser = insertUser("核查员" + System.nanoTime());
        permissionService.replaceFor(checkuser.getId(), java.util.List.of(PermissionService.GROUP_CHECKUSER), null);
        String page = "核查日志页" + System.nanoTime();

        // 带 CF 头的编辑请求：日志应记录 CF-Connecting-IP 与 User-Agent
        mockMvc.perform(post("/wiki/{title}", page)
                        .session(sessionOf(user))
                        .with(csrf())
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .header("User-Agent", "WikiKernelTest/1.0")
                        .param("content", "正文"))
                .andExpect(status().is3xxRedirection());

        WikiLog log = wikiLogMapper.selectList(Wrappers.<WikiLog>lambdaQuery()
                .eq(WikiLog::getActorUserId, user.getId())
                .orderByDesc(WikiLog::getId)).get(0);
        assertEquals("203.0.113.7", log.getIp());
        assertEquals("WikiKernelTest/1.0", log.getUserAgent());

        // 普通用户：看不到核查内容
        mockMvc.perform(get("/wiki/{title}", "特殊:用户核查")
                        .session(sessionOf(insertUser("核查无关" + System.nanoTime()))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("只有核查员")));

        // 核查员「按用户」查：显示该用户最近操作的 IP / UA；操作列的目标页面是可点击链接
        mockMvc.perform(get("/wiki/{title}", "特殊:用户核查")
                        .session(sessionOf(checkuser))
                        .param("user", user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("203.0.113.7")))
                .andExpect(content().string(containsString("WikiKernelTest/1.0")))
                .andExpect(content().string(containsString(cc.thonly.wikikernel.common.web.WikiUrls.pagePath(page))));

        // 核查员「按 IP」查：显示该 IP 上的用户名
        mockMvc.perform(get("/wiki/{title}", "特殊:用户核查")
                        .session(sessionOf(checkuser))
                        .param("ip", "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.getUsername())));

        // 核查员「按 CIDR」查
        mockMvc.perform(get("/wiki/{title}", "特殊:用户核查")
                        .session(sessionOf(checkuser))
                        .param("ip", "203.0.113.0/24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.getUsername())));

        // 不匹配的 IP：没有记录
        mockMvc.perform(get("/wiki/{title}", "特殊:用户核查")
                        .session(sessionOf(checkuser))
                        .param("ip", "198.51.100.1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有记录")));
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
