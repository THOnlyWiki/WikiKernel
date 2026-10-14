package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.plugin.LoadedPlugin;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件注册的「站点设置分区」（{@code common.extension.SiteSettingsSection}）的平台侧回归测试：
 * core 只渲染「键 → 值」并按分区声明的字段保存——**不认识这些键属于哪个功能**。
 * <p>
 * 这里用一个假的插件管理器提供一个测试分区，覆盖三件事：
 * <ol>
 *     <li>设置页把分区渲染出来（标题 / 字段 / 隐藏的分区标记 / 各控件类型）；</li>
 *     <li>{@code POST /api/site-settings} 按分区声明的键写站点设置（checkbox 未勾选 = false）；</li>
 *     <li>匿名 / 非管理员不能写（与其它站点设置一致）。</li>
 * </ol>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginSettingsSectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private cc.thonly.wikikernel.db.mapper.WikiUserMapper wikiUserMapper;

    @MockitoBean
    private PluginManager pluginManager;

    /** 假分区：三种字段类型各一个（键与「插件自己的设置键」形式一致）。 */
    private final SiteSettingsSection section = new SiteSettingsSection() {
        @Override
        public String id() {
            return "demo-plugin";
        }

        @Override
        public String title() {
            return "演示插件设置";
        }

        @Override
        public List<SettingField> fields() {
            return List.of(
                    SettingField.text("demo.text", "文本项", "默认值"),
                    SettingField.checkbox("demo.flag", "布尔项", false),
                    SettingField.textarea("demo.lines", "多行项", ""));
        }
    };

    @BeforeEach
    void stubPluginManager() {
        when(pluginManager.extensions(eq(SiteSettingsSection.class))).thenReturn(List.of(section));
    }

    /** 假「声明了功能开关的插件」：描述文件里的 plugin.featureKey / plugin.featureDefault 走这里。 */
    private LoadedPlugin featureSwitchPlugin() {
        PluginDescriptor descriptor = new PluginDescriptor("demo-switch", "Demo Switch", "1.0.0",
                "Demo plugin with a feature switch", List.of(), "", List.of(),
                "features.demo-switch", false);
        return new LoadedPlugin(descriptor, null, null, "demo-switch.jar", null, null);
    }

    @Test
    void declaredFeatureSwitchIsRenderedAndSavedAutomatically() throws Exception {
        // 插件只声明 plugin.featureKey/plugin.featureDefault：core 自动给一个复选框（键 = 声明的开关）
        LoadedPlugin plugin = featureSwitchPlugin();
        when(pluginManager.loadedPlugins()).thenReturn(List.of(plugin));
        when(pluginManager.find(eq("demo-switch"))).thenReturn(java.util.Optional.of(plugin));

        WikiUser admin = admin("开关分区管理员");
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"features.demo-switch\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"plugin-section\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"demo-switch\"")));

        // 保存：勾选 → true；再保存不勾选 → false（与其它复选框语义一致）
        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin))
                        .param("plugin-section", "demo-switch")
                        .param("features.demo-switch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AjaxCode.SUCCESS.getCode()));
        assertTrue(siteSettingsService.getBoolean("features.demo-switch", false), "勾选后写入 true");

        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin))
                        .param("plugin-section", "demo-switch"))
                .andExpect(status().isOk());
        assertFalse(siteSettingsService.getBoolean("features.demo-switch", true), "未勾选 = false");
    }

    @Test
    void adminSeesTheSectionAndCanSaveIt() throws Exception {
        WikiUser admin = admin("分区测试管理员");
        // 设置页渲染：标题 / 字段标签 / 隐藏的分区标记 / 三种控件
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("演示插件设置")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"plugin-section\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"demo.text\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"demo.flag\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"demo.lines\"")));
        assertTrue(siteSettingsService.get("demo.text", "").isEmpty(), "还没保存时是空的（渲染用默认值）");

        // 保存：文本 + 勾选的 checkbox + 多行文本
        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin))
                        .param("plugin-section", "demo-plugin")
                        .param("demo.text", "hello")
                        .param("demo.flag", "1")
                        .param("demo.lines", "第一行\n第二行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AjaxCode.SUCCESS.getCode()));
        assertTrue(siteSettingsService.get("demo.text", "").equals("hello"));
        assertTrue(siteSettingsService.getBoolean("demo.flag", false), "勾选的 checkbox 写成 true");
        assertTrue(siteSettingsService.get("demo.lines", "").contains("第一行"));

        // 再保存（不勾 checkbox）：写成 false（与其它站点设置复选框语义一致）
        mockMvc.perform(post("/api/site-settings").session(sessionOf(admin))
                        .param("plugin-section", "demo-plugin")
                        .param("demo.text", "")
                        .param("demo.lines", ""))
                .andExpect(status().isOk());
        assertTrue(siteSettingsService.get("demo.text", "").isEmpty(), "空串 = 清空");
        assertFalse(siteSettingsService.getBoolean("demo.flag", true), "未勾选 = false");
    }

    @Test
    void anonymousAndOrdinaryUsersCannotSaveSections() throws Exception {
        WikiUser user = insertUser("分区测试用户" + System.nanoTime());
        mockMvc.perform(post("/api/site-settings")
                        .param("plugin-section", "demo-plugin")
                        .param("demo.text", "hacked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AjaxCode.UNAUTHORIZED.getCode()));
        mockMvc.perform(post("/api/site-settings").session(sessionOf(user))
                        .param("plugin-section", "demo-plugin")
                        .param("demo.text", "hacked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AjaxCode.FORBIDDEN.getCode()));
        assertTrue(siteSettingsService.get("demo.text", "").isEmpty(), "没有权限的请求不能写设置");
    }

    // ==================== 工具 ====================

    private WikiUser admin(String prefix) {
        WikiUser admin = insertUser(prefix + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        return admin;
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
