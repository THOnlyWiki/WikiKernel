package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.config.ConfigCatalog;
import cc.thonly.wikikernel.config.ConfigCatalogEntry;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安装向导的「下载完整配置」（{@code GET /api/setup/config-template}）与「精简配置」：
 * <ul>
 *     <li>页面：{@code /init} 上有下载入口（中英文案都走 i18n）；</li>
 *     <li>下载：200 + {@code Content-Disposition} + {@code application/x-yaml}，
 *     内容是合法 YAML、键集合 = 配置目录全集、敏感项留空、绝不带本机真实密码；</li>
 *     <li>权限：未初始化时可用；已初始化（不在安装模式）且没解锁 → 403，解锁后可下载；</li>
 *     <li>向导写出的 {@code LocalSettings.yaml} 仍然是「精简配置」：只有向导配置过的项，
 *     不含配置全集（例如 {@code wikikernel.skins.*}），且能被 SnakeYAML 解析。</li>
 * </ul>
 * 独立上下文：auto 模式 + 临时配置文件路径 + 关闭自动重启（测试里不能真的重启 JVM）。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.setup.mode=auto",
        "wikikernel.setup.file=build/setup-config-template-test-LocalSettings.yaml",
        "wikikernel.setup.restart-enabled=false"
})
@AutoConfigureMockMvc
class SetupConfigTemplateTest {

    private static final Path CONFIG = Path.of("build", "setup-config-template-test-LocalSettings.yaml");

    /** 打包用的口令（用来验证「下载的模板里不会出现真实密码」）。 */
    private static final String EMAIL_SECRET = "smtp-password-must-not-leak";
    private static final String REDIS_SECRET = "redis-password-must-not-leak";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
        Files.deleteIfExists(CONFIG.resolveSibling("data/schemas/wikikernel-config-schema.json"));
    }

    // ==================== 页面入口 ====================

    /** /init 上有下载入口（中文），文案与链接都由 i18n / 路由生成。 */
    @Test
    void initPageOffersDownloadEntry() throws Exception {
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"setup-config-template\"")))
                .andExpect(content().string(containsString("href=\"/api/setup/config-template\"")))
                .andExpect(content().string(containsString("download")))
                .andExpect(content().string(containsString("下载完整配置")));

        mockMvc.perform(get("/init").param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Download full configuration")))
                .andExpect(content().string(containsString("href=\"/api/setup/config-template\"")))
                .andExpect(content().string(not(containsString("下载完整配置"))));
    }

    // ==================== 下载 ====================

    /** 未初始化时可直接下载：响应头齐全、内容是合法 YAML、键集合 = 配置目录全集、敏感项留空。 */
    @Test
    void templateEndpointServesFullYamlWithDownloadHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/setup/config-template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/x-yaml")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"LocalSettings.full.yaml\"")))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String firstLine = body.lines().findFirst().orElse("");
        assertTrue(firstLine.startsWith("# yaml-language-server: $schema="), firstLine);
        assertTrue(firstLine.endsWith("/schemas/wikikernel-config-schema.json"),
                "Schema 用请求所在站点拼绝对 URL（下载后 IDE 才能解析）：" + firstLine);

        Map<String, Object> parsed = parse(body);
        Set<String> expected = expectedKeys(ConfigCatalog.get());
        Set<String> actual = flatten(parsed);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), "模板缺少配置键：" + missing);
        assertTrue(actual.containsAll(ConfigCatalog.get().keys()),
                "模板应包含配置目录全集（" + ConfigCatalog.get().keys().size() + " 项）");
        assertTrue(body.lines().count() > 200, "完整模板应包含全部配置项与中文说明");

        // 首行指向的 Schema 必须真的能取到（安装模式下也要放行静态资源，否则 IDE 会 302 到 /init）
        mockMvc.perform(get(ConfigCatalog.SCHEMA_URL_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"wikikernel\"")));

        // 敏感项：留空（绝不写明文）
        for (ConfigCatalogEntry entry : ConfigCatalog.get().entries()) {
            if (!entry.secret()) {
                continue;
            }
            assertEquals(ConfigCatalog.SECRET_PLACEHOLDER, valueOf(parsed, entry.key()),
                    "敏感项必须留空：" + entry.key());
        }
        assertFalse(body.contains(EMAIL_SECRET), "模板不应包含任何真实密码");
        assertFalse(body.contains(REDIS_SECRET), "模板不应包含任何真实密码");
    }

    /** 向导提交过邮件 / Redis 密码后下载的模板里也不能出现这些明文（模板只来自配置目录）。 */
    @Test
    void templateEndpointNeverLeaksConfiguredSecrets() throws Exception {
        saveCoreWithSecrets();

        MvcResult result = mockMvc.perform(get("/api/setup/config-template"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(EMAIL_SECRET), "下载的模板里出现了邮件密码明文");
        assertFalse(body.contains(REDIS_SECRET), "下载的模板里出现了 Redis 密码明文");
    }

    /** 已初始化（不在安装模式）且未解锁：403，不新开泄漏通道；解锁后可以下载。 */
    @Test
    void templateEndpointIsRejectedAfterSetup() throws Exception {
        saveCoreWithSecrets();
        MvcResult finishResult = mockMvc.perform(post("/api/setup/finish"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String key = JsonPath.read(finishResult.getResponse().getContentAsString(), "$.data.key");
        assertTrue(Files.exists(CONFIG), "应生成配置文件");

        // 未解锁：403（与其它 /api/setup/* 一致）
        mockMvc.perform(get("/api/setup/config-template"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 解锁后：200，且仍然不含真实密码
        MockHttpSession unlocked = new MockHttpSession();
        mockMvc.perform(get("/init").param("key", key).session(unlocked))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/init"));
        String body = mockMvc.perform(get("/api/setup/config-template").session(unlocked))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("# yaml-language-server: $schema="), "解锁后应能下载完整模板");
        assertFalse(body.contains(EMAIL_SECRET), "解锁下载的模板里也不应有真实密码");

        // 真实配置里有 Redis 密码（说明上面的断言不是「配置本来就没有密码」）
        assertTrue(Files.readString(CONFIG, StandardCharsets.UTF_8).contains(REDIS_SECRET),
                "向导写出的 LocalSettings.yaml 里应有 Redis 密码（模板与它无关）");
    }

    // ==================== 精简配置 ====================

    /** 向导生成的是精简 YAML：合法、只含向导项（不含配置全集），且没有锚点 / 别名。 */
    @Test
    void generatedLocalSettingsStaysMinimal() throws Exception {
        saveCoreWithSecrets();
        mockMvc.perform(post("/api/setup/finish")).andExpect(jsonPath("$.code").value(200));

        String raw = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(raw.startsWith("# yaml-language-server: $schema="), raw.lines().findFirst().orElse(""));
        assertFalse(raw.contains("&id001"), "不应输出锚点 / 别名：\n" + raw);

        Map<String, Object> parsed = parse(raw);
        Set<String> keys = flatten(parsed);
        // 向导配置过的项都在
        for (String key : new String[]{"wikikernel.database.type", "wikikernel.database.file",
                "wikikernel.setup.key", "wikikernel.features.register",
                "wikikernel.cache.page.enabled", "wikikernel.cache.page.backend",
                "wikikernel.email.enabled", "wikikernel.email.host", "wikikernel.email.password",
                "spring.data.redis.host", "spring.data.redis.port", "spring.data.redis.password"}) {
            assertTrue(keys.contains(key), "精简配置里应包含向导项：" + key);
        }
        // 没配置过的项一个都不该出现（这正是「不再写 148 项全集」的保证）
        for (String key : new String[]{"wikikernel.skins.default-skin", "wikikernel.skins.disabled",
                "wikikernel.ai.providers", "wikikernel.ai.max-steps", "wikikernel.plugins.dir",
                "wikikernel.security.rate-limit.login.max-attempts", "wikikernel.typescript.target",
                "wikikernel.wikitext.max-template-depth", "wikikernel.cors.enabled"}) {
            assertFalse(keys.contains(key), "精简配置里不应出现未配置的项：" + key);
        }
        assertTrue(keys.size() < ConfigCatalog.get().keys().size() / 2,
                "精简配置的键数（" + keys.size() + "）应远小于配置全集（"
                        + ConfigCatalog.get().keys().size() + "）：\n" + raw);
    }

    // ==================== 辅助 ====================

    /** 提交一次「核心设置」：SQLite + 管理员 + 邮件（带密码）+ 开启 Redis 缓存（带密码）。 */
    private void saveCoreWithSecrets() throws Exception {
        mockMvc.perform(post("/api/setup/core")
                        .param("databaseType", "sqlite")
                        .param("databaseFile", "./data/wiki.db")
                        .param("adminUsername", "TemplateAdmin")
                        .param("adminPassword", "secret123")
                        .param("emailEnabled", "true")
                        .param("emailHost", "smtp.example.com")
                        .param("emailPassword", EMAIL_SECRET)
                        .param("cacheEnabled", "true")
                        .param("cacheBackend", "redis")
                        .param("cacheRedisHost", "redis.example.com")
                        .param("cacheRedisPassword", REDIS_SECRET))
                .andExpect(jsonPath("$.code").value(200));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        Object loaded = new Yaml().load(text);
        assertNotNull(loaded, "配置应是合法 YAML");
        assertTrue(loaded instanceof Map, "配置应是 YAML 映射");
        return (Map<String, Object>) loaded;
    }

    /** 目录全集 + 沿途的中间分组段。 */
    private static Set<String> expectedKeys(ConfigCatalog catalog) {
        Set<String> keys = new LinkedHashSet<>();
        for (ConfigCatalogEntry entry : catalog.entries()) {
            StringBuilder path = new StringBuilder();
            for (String segment : entry.key().split("\\.")) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(segment);
                keys.add(path.toString());
            }
        }
        return keys;
    }

    /** 展平解析结果（只往映射里下钻；空映射 / 空列表算叶子）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> flatten(Map<String, Object> root) {
        Set<String> keys = new LinkedHashSet<>();
        collect(root, "", keys);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, String prefix, Set<String> keys) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            keys.add(path);
            if (entry.getValue() instanceof Map<?, ?> child) {
                collect((Map<String, Object>) child, path, keys);
            }
        }
    }

    /** 按点分键取值（缺失返回 null）。 */
    private static Object valueOf(Map<String, Object> root, String key) {
        Object current = root;
        for (String segment : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
