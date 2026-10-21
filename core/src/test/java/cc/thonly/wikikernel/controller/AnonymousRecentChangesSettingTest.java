package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteSettingsService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点设置「允许未登录查看最近更改」：
 * LocalSettings 导入为 false → 匿名只看到登录提示，登录用户正常；管理员可在设置 API 里随时打开。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.permissions.allow-anonymous-recent-changes=false"
})
@AutoConfigureMockMvc
class AnonymousRecentChangesSettingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void importedSettingBlocksAnonymousButNotLoggedInUsers() throws Exception {
        assertFalse(siteSettingsService.getBoolean(
                SiteSettingsService.ALLOW_ANONYMOUS_RECENT_CHANGES_KEY, true));

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请先登录后再查看最近更改。")))
                .andExpect(content().string(not(containsString("激活的过滤器"))));

        WikiUser user = insertUser("最近更改用户" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("激活的过滤器")))
                .andExpect(content().string(not(containsString("请先登录后再查看最近更改。"))));

        // 设置 API：匿名 401、普通用户 403、管理员 200
        mockMvc.perform(post("/api/site-settings").param("allowAnonymousRecentChanges", "1"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/site-settings").session(sessionOf(user))
                        .param("allowAnonymousRecentChanges", "1"))
                .andExpect(jsonPath("$.code").value(403));

        WikiUser admin = insertUser("最近更改管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin))
                        .param("allowAnonymousRecentChanges", "1"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("激活的过滤器")))
                .andExpect(content().string(not(containsString("请先登录后再查看最近更改。"))));
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
