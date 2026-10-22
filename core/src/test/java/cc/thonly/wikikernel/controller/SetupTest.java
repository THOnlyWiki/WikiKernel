package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.service.WikiPageService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安装向导（{@code /init}）：
 * 配置文件不存在 → 页面 302 到 /init、API 409；核心未完成时插件初始化 409；
 * 完成后生成 LocalSettings.yaml（数据库 / 初始账号 / 邮件 / 插件配置 / 随机密钥），安装模式自动关闭；
 * 已安装后 /init 需要密钥解锁（{@code ?key=…} 或密钥表单），解锁可重新初始化插件并把配置合并写回。
 * <p>
 * 独立上下文：auto 模式 + 临时配置文件路径 + 关闭自动重启（测试里不能真的重启 JVM）。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.setup.mode=auto",
        "wikikernel.setup.file=build/setup-test-LocalSettings.yaml",
        "wikikernel.setup.restart-enabled=false"
})
@AutoConfigureMockMvc
class SetupTest {

    private static final Path CONFIG = Path.of("build", "setup-test-LocalSettings.yaml");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    /** 测试用插件安装步骤（模拟插件注册 SetupStep）。 */
    @TestConfiguration
    static class TestSetupSteps {
        @Bean
        SetupStep demoSetupStep() {
            return new SetupStep() {
                @Override
                public String id() {
                    return "demo-plugin";
                }

                @Override
                public String displayName() {
                    return "Demo Plugin";
                }

                @Override
                public List<SetupField> fields() {
                    return List.of(SetupField.text("greeting", "问候语", "你好"));
                }

                @Override
                public SetupResult apply(Map<String, String> values, SetupContext context) {
                    context.putPluginSetting("greeting", values.getOrDefault("greeting", ""));
                    context.putGlobal("wikikernel.sidebar.page", "WikiKernel:Sidebar");
                    return SetupResult.ok("ok");
                }
            };
        }
    }

    @BeforeEach
    @AfterEach
    void cleanConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    @Test
    void setupFlowGatesPluginTabsWritesConfigAndKeepsKeyAccess() throws Exception {
        wikiPageService.editPage(null, "安装测试页", "正文");

        // 1) 安装模式：普通页面 302 到 /init，API 409；/init、静态资源、/api/version 放行
        mockMvc.perform(get("/wiki/安装测试页"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/init"));
        mockMvc.perform(post("/api/pages/move").param("from", "a").param("to", "b"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("安装")))
                .andExpect(content().string(containsString("id=\"setup-core-form\"")))
                .andExpect(content().string(containsString("id=\"setup-finish\"")))
                .andExpect(content().string(containsString("value=\"mariadb\"")));
        mockMvc.perform(get("/js/setup.js")).andExpect(status().isOk());
        mockMvc.perform(get("/api/version")).andExpect(status().isOk());

        // 2) 核心未完成：插件初始化 409
        mockMvc.perform(post("/api/setup/plugin/demo-plugin").param("greeting", "hi"))
                .andExpect(jsonPath("$.code").value(409));

        // 3) 核心设置：校验 + 保存
        mockMvc.perform(post("/api/setup/core").param("databaseType", "oracle"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/setup/core")
                        .param("databaseType", "sqlite")
                        .param("databaseFile", "./data/wiki.db")
                        .param("adminUsername", "SetupAdmin")
                        .param("adminPassword", "secret123")
                        .param("adminEmail", "admin@example.com")
                        .param("emailEnabled", "true")
                        .param("emailHost", "smtp.example.com")
                        .param("emailPort", "587")
                        .param("userInteraction", "true")
                        .param("comments", "false")
                        .param("widgets", "false")
                        .param("register", "false")
                        .param("abuseFilter", "false"))
                .andExpect(jsonPath("$.code").value(200));

        // 4) 插件初始化（核心完成后可访问）
        mockMvc.perform(post("/api/setup/plugin/demo-plugin").param("greeting", "hello"))
                .andExpect(jsonPath("$.code").value(200));

        // 5) 完成：写入 YAML（数据库 / 初始账号密码哈希 / 邮件 / 插件配置 / 随机密钥）
        MvcResult finishResult = mockMvc.perform(post("/api/setup/finish"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.restarting").value(false))
                .andExpect(jsonPath("$.data.key").isNotEmpty())
                .andReturn();
        String key = JsonPath.read(finishResult.getResponse().getContentAsString(), "$.data.key");
        assertTrue(Files.exists(CONFIG), "应生成配置文件");
        String yaml = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("type: sqlite"), yaml);
        assertTrue(yaml.contains("file: ./data/wiki.db"), yaml);
        assertTrue(yaml.contains("SetupAdmin"), yaml);
        assertTrue(yaml.contains("password-hash"), yaml);
        // 单个初始账号同时拥有「管理员 + 行政员」
        assertTrue(yaml.contains("管理员"), yaml);
        assertTrue(yaml.contains("行政员"), yaml);
        assertTrue(yaml.contains("smtp.example.com"), yaml);
        assertTrue(yaml.contains("greeting: hello"), yaml);
        // core 自己的功能开关（注册）写进 wikikernel.features；插件开关由插件在描述文件里声明
        // （plugin.featureKey）后由 core 的向导 tab 自动写，不再是 core 的字段
        assertTrue(yaml.contains("features:"), yaml);
        assertTrue(yaml.contains("register: false"), yaml);
        assertTrue(yaml.contains(key), "密钥应写入 YAML");

        // 6) 配置文件已存在：安装模式关闭，不再跳 /init
        mockMvc.perform(get("/wiki/安装测试页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("正文")));

        // 7) 已安装：/init 需要密钥（表单页），错误密钥提示；未解锁时接口 403
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"setup-unlock-form\"")));
        mockMvc.perform(get("/init").param("key", "wrong-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("密钥不正确")));
        mockMvc.perform(post("/api/setup/plugin/demo-plugin").param("greeting", "x"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/setup/finish"))
                .andExpect(jsonPath("$.code").value(403));

        // 8) 密钥解锁（会话）→ 重新初始化插件 → 再次写配置：核心设置保留、插件设置更新
        MockHttpSession unlocked = new MockHttpSession();
        mockMvc.perform(get("/init").param("key", key).session(unlocked))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/init"));
        mockMvc.perform(get("/init").session(unlocked))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"setup-finish\"")))
                // 初始化后仍可改「邮件 + 功能开关」，并预填当前值
                .andExpect(content().string(containsString("id=\"setup-core-form\"")))
                .andExpect(content().string(containsString("id=\"setup-email-host\"")))
                .andExpect(content().string(containsString("value=\"smtp.example.com\"")))
                // 插件开关不再由 core 的向导渲染（插件在描述文件里声明 plugin.featureKey 后由插件 tab 自动带出）
                .andExpect(content().string(not(containsString("id=\"setup-user-interaction\""))))
                .andExpect(content().string(not(containsString("id=\"setup-comments\""))))
                .andExpect(content().string(not(containsString("id=\"setup-widgets\""))))
                .andExpect(content().string(containsString("id=\"setup-register\"")))
                .andExpect(content().string(not(containsString("id=\"setup-abuse-filter\""))))
                // 数据库与初始账号不再显示（改它们请编辑 LocalSettings.yaml）
                .andExpect(content().string(not(containsString("id=\"setup-db-type\""))))
                .andExpect(content().string(not(containsString("id=\"setup-admin-username\""))));
        mockMvc.perform(post("/api/setup/plugin/demo-plugin").session(unlocked)
                        .param("greeting", "hello-again"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/setup/finish").session(unlocked))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.key").value(key));
        String merged = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(merged.contains("type: sqlite"), "核心配置应保留");
        assertTrue(merged.contains("SetupAdmin"), "初始账号应保留");
        assertTrue(merged.contains("hello-again"), "插件设置应更新");

        // 9) 初始化后修改「邮件 + 功能开关」：写回 YAML，数据库 / 初始账号保持不变
        mockMvc.perform(post("/api/setup/core").session(unlocked)
                        .param("emailEnabled", "true")
                        .param("emailHost", "smtp2.example.com")
                        .param("emailPort", "2525")
                        .param("comments", "true")
                        .param("widgets", "true")
                        .param("register", "true")
                        .param("abuseFilter", "true"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/setup/finish").session(unlocked))
                .andExpect(jsonPath("$.code").value(200));
        String updated = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(updated.contains("smtp2.example.com"), "邮件应更新");
        // 插件开关不再由 core 的向导写（插件 tab / plugin.featureKey 负责），core 自己的开关照旧
        assertTrue(updated.contains("register: true"), "注册开关应更新");
        assertTrue(updated.contains("type: sqlite"), "数据库配置应保留");
        assertTrue(updated.contains("SetupAdmin"), "初始账号应保留");
    }
}
