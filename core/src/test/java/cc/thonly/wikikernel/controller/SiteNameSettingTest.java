package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.lua.LuaSiteInfoProvider;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点名称（设置键 {@code site.name}，YAML 里是 {@code wikikernel.site.name}）：
 * 默认 {@code WikiKernel}；「特殊:权限配置 → 站点设置」保存后，浏览器标题（「页面名 - 站点名」）、
 * 页眉图标提示、搜索框占位符、动作 API 的 {@code sitename} 与 Lua 的 {@code mw.site.siteName} 全部跟着变；
 * 空值 / 控制字符 / 超长会被规范化。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files"
})
@AutoConfigureMockMvc
class SiteNameSettingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private SiteNameService siteNameService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private LuaSiteInfoProvider luaSiteInfoProvider;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void normalizeFallsBackAndLimits() {
        assertEquals(SiteNameService.DEFAULT_NAME, SiteNameService.normalize(null));
        assertEquals(SiteNameService.DEFAULT_NAME, SiteNameService.normalize("   "));
        assertEquals(SiteNameService.DEFAULT_NAME, SiteNameService.normalize("\t\n"));
        assertEquals("我的 维基", SiteNameService.normalize("  我的\n维基  "));
        assertEquals(SiteNameService.MAX_LENGTH, SiteNameService.normalize("x".repeat(500)).length());
    }

    @Test
    void defaultSiteNameIsUsedWhenUnset() throws Exception {
        String original = siteSettingsService.get(SiteNameService.KEY, "");
        siteSettingsService.set(SiteNameService.KEY, "");
        try {
            String title = "站点名称默认页" + System.nanoTime();
            wikiPageService.editPage(null, title, "内容");
            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(
                            "<title>" + title + " - " + SiteNameService.DEFAULT_NAME + "</title>")))
                    .andExpect(content().string(containsString(
                            "wgSiteName\":\"" + SiteNameService.DEFAULT_NAME + "\"")))
                    .andExpect(content().string(containsString(
                            "alt=\"" + SiteNameService.DEFAULT_NAME + "\"")))
                    .andExpect(content().string(containsString(
                            "placeholder=\"搜索 " + SiteNameService.DEFAULT_NAME + "\"")));
            mockMvc.perform(get("/api.php").param("action", "query").param("meta", "siteinfo"))
                    .andExpect(jsonPath("$.query.general.sitename").value(SiteNameService.DEFAULT_NAME));
            assertEquals(SiteNameService.DEFAULT_NAME, siteNameService.name());
            assertEquals(SiteNameService.DEFAULT_NAME, luaSiteInfoProvider.siteName());
        } finally {
            siteSettingsService.set(SiteNameService.KEY, original);
        }
    }

    @Test
    void customSiteNameFlowsToTitleBrandSearchApiAndLua() throws Exception {
        String original = siteSettingsService.get(SiteNameService.KEY, "");
        String name = "演示维基" + System.nanoTime();
        WikiUser admin = admin("站点名称管理员");
        try {
            mockMvc.perform(post("/api/site-settings").session(sessionOf(admin)).param("siteName", name))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(name, siteNameService.name());

            String title = "站点名称自定页" + System.nanoTime();
            wikiPageService.editPage(null, title, "内容");
            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("<title>" + title + " - " + name + "</title>")))
                    .andExpect(content().string(containsString("wgSiteName\":\"" + name + "\"")))
                    .andExpect(content().string(containsString("alt=\"" + name + "\"")))
                    .andExpect(content().string(containsString("placeholder=\"搜索 " + name + "\"")))
                    .andExpect(content().string(containsString(name + " · Powered by Spring Boot")));

            mockMvc.perform(get("/api.php").param("action", "query").param("meta", "siteinfo"))
                    .andExpect(jsonPath("$.query.general.sitename").value(name));
            assertEquals(name, luaSiteInfoProvider.siteName());

            // 站点设置页（管理员）显示「站点信息」分组与当前名称
            mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"settings-site-name\"")))
                    .andExpect(content().string(containsString("name=\"siteName\"")))
                    .andExpect(content().string(containsString("value=\"" + name + "\"")))
                    .andExpect(content().string(containsString("站点信息")));

            // 空白值 = 回到默认名
            mockMvc.perform(post("/api/site-settings").session(sessionOf(admin)).param("siteName", "   "))
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(SiteNameService.DEFAULT_NAME, siteNameService.name());
        } finally {
            siteSettingsService.set(SiteNameService.KEY, original);
        }
    }

    @Test
    void apiLeavesSiteNameAloneWhenFieldIsMissing() throws Exception {
        String original = siteSettingsService.get(SiteNameService.KEY, "");
        String name = "保留维基" + System.nanoTime();
        WikiUser admin = admin("站点名称保留管理员");
        siteSettingsService.set(SiteNameService.KEY, name);
        try {
            // 老客户端 / 只改开关的调用不带 siteName：站点名称保持不变
            mockMvc.perform(post("/api/site-settings").session(sessionOf(admin)).param("popups", "1"))
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(name, siteNameService.name());
        } finally {
            siteSettingsService.set(SiteNameService.KEY, original);
        }
    }

    // ==================== 工具 ====================

    private WikiUser admin(String prefix) {
        WikiUser user = new WikiUser();
        user.setUsername(prefix + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
