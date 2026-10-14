package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 代理已迁到插件 {@code ai-agent}：**core 未加载插件时**
 * {@code 特殊:AI代理} 与 {@code /api/ai-agent/*}、{@code /api/ai-keys/*} 都不存在，
 * 参数设置里没有「AI 代理」标签页、站点设置里没有 AI 厂商密钥表单，
 * 页面里也不会注入 {@code /plugins/ai-agent/…} 资源。
 * <p>
 * 迁移后 core 只保留：
 * <ul>
 *     <li>站点设置开关 {@code features.ai-agent}（默认关闭，由 {@code FeatureService} / 站点设置页 / {@code /init}
 *     维护，{@code /init} 的复选框照旧可用）；</li>
 *     <li>AI 版本的页面元数据：{@code wiki_revision.ai_assisted} / {@code ai_model} 与词条底部的隐藏水印
 *     （{@code .mw-ai-watermark} / {@code aiAgent.watermarkTitle}）。</li>
 * </ul>
 * 插件自己的配置（{@code wikikernel.ai.*}、站点放开普通用户的键 {@code ai-agent.allow-normal-users}、
 * {@code wiki_user_ai_key} 表）都随插件走：core 不再认识它们（只有通用设置存储）。
 * 平台的提交路径（{@code common.service.EditSubmitter}，AI 代理确认提交用它）由
 * {@code PluginDataBridgeTest} 覆盖；插件的完整行为（厂商解析 / 会话编排 / 工具与提案 / 密钥优先级 /
 * 开关关闭时的 403）见 {@code plugin/ai-agent/src/test}。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files",
        // 就算 yaml 里配了 mock 厂商，core 自己也不再有任何 AI 对话能力（只有插件认识这些配置）
        "wikikernel.ai.providers.mock.type=mock",
        "wikikernel.ai.default-provider=mock"
})
@AutoConfigureMockMvc
class AiAgentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FeatureService featureService;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Test
    void featureSwitchStillExistsWithoutThePlugin() {
        // 开关本身仍在（/init、站点设置、LocalSettings.yaml 都写这个键），只是没有插件就没有功能
        siteSettingsService.set("features.ai-agent", Boolean.toString(true));
        assertTrue(siteSettingsService.getBoolean("features.ai-agent", false), "features.ai-agent 开关应仍然可用");
        assertEquals("features.ai-agent", "features.ai-agent");
        siteSettingsService.set("features.ai-agent", Boolean.toString(false));

        // 站点放开普通用户的键（插件迁移后由 ai-agent 自己维护，键名保持兼容）；
        // yaml 的 wikikernel.ai.* 与这张表都归插件，core 只管通用存储
        siteSettingsService.set("ai-agent.allow-normal-users", "true");
        assertEquals("true", siteSettingsService.get("ai-agent.allow-normal-users", ""));
        siteSettingsService.set("ai-agent.allow-normal-users", "false");
    }

    @Test
    void specialPageAndApisDoNotExistWithoutThePlugin() throws Exception {
        siteSettingsService.set("features.ai-agent", Boolean.toString(true));
        try {
            WikiUser agent = agentUser("无插件代理用户");

            // 特殊:AI代理 由插件提供：core 未加载插件时该特殊页不存在
            mockMvc.perform(get("/wiki/{title}", "特殊:AI代理").session(sessionOf(agent)))
                    .andExpect(status().isNotFound());
            // 别名同样不存在
            mockMvc.perform(get("/wiki/{title}", "Special:AiAgent").session(sessionOf(agent)))
                    .andExpect(status().isNotFound());

            // /api/ai-agent/* 与 /api/ai-keys/* 由插件的 @RestController 提供
            mockMvc.perform(get("/api/ai-agent/state").session(sessionOf(agent)))
                    .andExpect(status().is4xxClientError());
            mockMvc.perform(post("/api/ai-agent/chat").with(csrf()).session(sessionOf(agent))
                            .param("message", "你好"))
                    .andExpect(status().is4xxClientError());
            mockMvc.perform(post("/api/ai-keys").with(csrf()).session(sessionOf(agent))
                            .param("scope", "user").param("provider", "mock"))
                    .andExpect(status().is4xxClientError());
            mockMvc.perform(post("/api/ai-keys/allow-normal").with(csrf()).session(sessionOf(agent))
                            .param("allow", "1"))
                    .andExpect(status().is4xxClientError());
            // 迁移前的旧路径也不再存在
            mockMvc.perform(post("/api/ai-provider-keys").with(csrf()).session(sessionOf(agent))
                            .param("scope", "user").param("provider", "mock"))
                    .andExpect(status().is4xxClientError());
        } finally {
            siteSettingsService.set("features.ai-agent", Boolean.toString(false));
        }
    }

    @Test
    void preferencesAndSiteSettingsHaveNoAiKeysUiWithoutThePlugin() throws Exception {
        siteSettingsService.set("features.ai-agent", Boolean.toString(true));
        try {
            WikiUser admin = insertUser("无插件AI管理员");
            permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));

            // 参数设置：AI 标签页由插件的 PreferenceSection 贡献，core 未加载插件时没有
            mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(sessionOf(admin)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("data-target=\"ai\""))))
                    .andExpect(content().string(not(containsString("preferences-ai-form"))))
                    .andExpect(content().string(not(containsString("ai-key-row"))));

            // 站点设置：AI 厂商（站点密钥）表单已迁到插件（特殊:AI代理?keys=1）；
            // AI 功能开关现在也由插件在描述文件里声明（plugin.featureKey），core 的设置页不再内置它——
            // 未加载插件时表单里没有这个复选框（加载插件后由 core 自动渲染，见 PluginSettingsSectionTest）
            mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("name=\"aiAgent\""))))
                    .andExpect(content().string(not(containsString("site-ai-keys-form"))))
                    .andExpect(content().string(not(containsString("ai-key-row"))));

            // 页面上不再注入 AI 的脚本 / 样式（改由插件 HtmlHeadProvider 注入）
            String page = "无插件AI资源页" + System.nanoTime();
            wikiPageService.editPage(null, page, "正文\n");
            mockMvc.perform(get("/wiki/{title}", page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("正文")))
                    .andExpect(content().string(not(containsString("/js/ai-agent.js"))))
                    .andExpect(content().string(not(containsString("/js/ai-keys.js"))))
                    .andExpect(content().string(not(containsString("/plugins/ai-agent/"))));
        } finally {
            siteSettingsService.set("features.ai-agent", Boolean.toString(false));
        }
    }

    @Test
    void aiWatermarkMetadataStillRenderedByCore() throws Exception {
        // 页面元数据（版本上的 ai_assisted / ai_model）继续留在 core：词条底部照旧渲染隐藏水印
        String title = "无插件水印页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "正文\n");
        wikiPageService.markAiAssisted(page.getLatestRevisionId(), "mock/mock");

        String html = mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("mw-ai-watermark"), "页面应含隐藏水印元素");
        assertTrue(html.contains("data-ai-assisted=\"1\""), html);
        assertTrue(html.contains("data-ai-model=\"mock/mock\""), html);
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        permissionService.grantDefault(user.getId());
        return user;
    }

    private WikiUser agentUser(String prefix) {
        WikiUser user = insertUser(prefix + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_AI_AGENT));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
