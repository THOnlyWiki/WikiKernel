package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安装向导（{@code /init}）的「缓存（可选）」区块：页面正文缓存（Redis 可选）。
 * <ul>
 *     <li>渲染：区块与全部字段（开关 / 后端 / Redis 主机 / 端口 / 库号 / 密码 / 键前缀）、默认值、
 *     中英切换（含校验错误的本地化文案）；</li>
 *     <li>保存：合法值写进 LocalSettings.yaml 的 {@code wikikernel.cache.page.*} 与
 *     {@code spring.data.redis.*}，开关 / 后端同时写站点设置（当前进程内立即生效）；</li>
 *     <li>校验：非法端口 / 库号 / 后端 / 键前缀一律 400 且带本地化错误，不静默忽略；</li>
 *     <li>关闭缓存时不写 Redis 连接项（开箱即安全）。</li>
 * </ul>
 * 独立上下文：auto 模式 + 临时配置文件路径 + 关闭自动重启（测试里不能真的重启 JVM）。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.setup.mode=auto",
        "wikikernel.setup.file=build/setup-cache-test-LocalSettings.yaml",
        "wikikernel.setup.restart-enabled=false"
})
@AutoConfigureMockMvc
class SetupCacheTest {

    private static final Path CONFIG = Path.of("build", "setup-cache-test-LocalSettings.yaml");

    /** 测试里用的 Redis 密码（只用来断言落盘，占位符性质）。 */
    private static final String REDIS_PASSWORD = "dummy-redis-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private PageCacheService pageCacheService;

    @BeforeEach
    @AfterEach
    void cleanConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    // ==================== 渲染 ====================

    /** 未安装时 /init 直接显示新区块：字段齐全、默认值正确、默认关闭（开箱即安全）。 */
    @Test
    void initRendersCacheSectionWithAllFieldsAndDefaults() throws Exception {
        // 先保存一次核心设置（不带缓存参数 = 默认关闭），让开关状态不依赖其它用例的执行顺序
        saveCoreWithoutCache();

        String html = mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("缓存（可选）")))
                .andExpect(content().string(containsString("启用页面正文缓存")))
                .andExpect(content().string(containsString("name=\"cacheEnabled\"")))
                .andExpect(content().string(containsString("name=\"cacheBackend\"")))
                .andExpect(content().string(containsString("name=\"cacheRedisHost\"")))
                .andExpect(content().string(containsString("name=\"cacheRedisPort\"")))
                .andExpect(content().string(containsString("name=\"cacheRedisDatabase\"")))
                .andExpect(content().string(containsString("name=\"cacheRedisPassword\"")))
                .andExpect(content().string(containsString("name=\"cacheRedisKeyPrefix\"")))
                // 连接不可用时的行为说明（auto 连不上自动退回内存）
                .andExpect(content().string(containsString("自动退回进程内缓存")))
                .andReturn().getResponse().getContentAsString();

        // 默认关闭：复选框不勾选
        assertTrue(tagOf(html, "setup-cache-enabled").contains("type=\"checkbox\""),
                tagOf(html, "setup-cache-enabled"));
        assertTrue(!tagOf(html, "setup-cache-enabled").contains("checked"),
                "缓存默认关闭，复选框不应勾选：" + tagOf(html, "setup-cache-enabled"));
        // 后端默认 auto
        assertEquals("auto", selectedOption(html, "setup-cache-backend"));
        // Redis 连接默认值（主机 / 端口 / 库号 / 键前缀）
        assertTrue(tagOf(html, "setup-cache-redis-host").contains("value=\"127.0.0.1\""),
                tagOf(html, "setup-cache-redis-host"));
        assertTrue(tagOf(html, "setup-cache-redis-port").contains("value=\"6379\""),
                tagOf(html, "setup-cache-redis-port"));
        assertTrue(tagOf(html, "setup-cache-redis-database").contains("value=\"0\""),
                tagOf(html, "setup-cache-redis-database"));
        assertTrue(tagOf(html, "setup-cache-redis-key-prefix")
                        .contains("value=\"wikikernel:pagecache:\""),
                tagOf(html, "setup-cache-redis-key-prefix"));
        // 密码框：type=password，且不回显任何密码
        assertTrue(tagOf(html, "setup-cache-redis-password").contains("type=\"password\""),
                tagOf(html, "setup-cache-redis-password"));
        assertTrue(!html.contains("cacheRedisPassword\" value=\""),
                "密码框不应回显密码");
        // 后端四个取值都在（选项文案复用站点设置的 settings.cachePageBackend* 键）
        for (String backend : new String[]{"auto", "redis", "memory", "none"}) {
            assertTrue(html.contains("value=\"" + backend + "\""), "缺少后端选项 " + backend);
        }
    }

    /** 中英切换：区块、字段与校验错误都有对应文案（键集合一致）。 */
    @Test
    void cacheSectionRendersInEnglish() throws Exception {
        // 语言由 WikiLocaleResolver 决定（?uselang=zh|en，MediaWiki 风格），不看 Accept-Language
        mockMvc.perform(get("/init").param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cache (optional)")))
                .andExpect(content().string(containsString("Enable the page content cache")))
                .andExpect(content().string(containsString("Redis host:")))
                .andExpect(content().string(containsString("Redis database (0-15):")))
                .andExpect(content().string(containsString("Key prefix")))
                .andExpect(content().string(not(containsString("缓存（可选）"))));

        // 校验错误也走本地化（英文）：非法后端
        mockMvc.perform(coreRequest("en").param("cacheBackend", "memcached"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Invalid cache backend (must be auto / redis / memory / none)"));
    }

    // ==================== 保存 ====================

    /** 合法值：写进 LocalSettings.yaml，且开关 / 后端在当前进程内立即生效。 */
    @Test
    void validCacheValuesAreWrittenToConfigAndAppliedImmediately() throws Exception {
        mockMvc.perform(post("/api/setup/core")
                        .param("databaseType", "sqlite")
                        .param("databaseFile", "./data/wiki.db")
                        .param("adminUsername", "CacheAdmin")
                        .param("adminPassword", "secret123")
                        .param("cacheEnabled", "true")
                        .param("cacheBackend", "redis")
                        .param("cacheRedisHost", "redis.example.com")
                        .param("cacheRedisPort", "6380")
                        .param("cacheRedisDatabase", "3")
                        .param("cacheRedisPassword", REDIS_PASSWORD)
                        .param("cacheRedisKeyPrefix", "site-a"))
                .andExpect(jsonPath("$.code").value(200));

        // 立即生效（不必等重启）：写站点设置，缓存实现每次读写都重新取配置
        assertTrue(siteSettingsService.getBoolean(PageCacheService.ENABLED_KEY, false),
                "保存后站点设置里的开关应为 true（立即生效）");
        assertEquals("redis", siteSettingsService.get(PageCacheService.BACKEND_KEY, ""));
        assertTrue(pageCacheService.enabled(), "当前进程内应立即启用页面正文缓存");

        mockMvc.perform(post("/api/setup/finish")).andExpect(jsonPath("$.code").value(200));
        assertTrue(Files.exists(CONFIG), "应生成配置文件");

        Map<String, Object> yaml = loadYaml();
        Map<String, Object> cachePage = section(yaml, "wikikernel", "cache", "page");
        assertEquals(Boolean.TRUE, cachePage.get("enabled"));
        assertEquals("redis", cachePage.get("backend"));
        assertEquals(Boolean.TRUE, section(cachePage, "redis").get("enabled"));
        // 键前缀自动补结尾冒号（与 PageCacheProperties 的归一化一致）
        assertEquals("site-a:", section(cachePage, "redis").get("key-prefix"));
        Map<String, Object> connection = section(yaml, "spring", "data", "redis");
        assertEquals("redis.example.com", connection.get("host"));
        assertEquals(6380, ((Number) connection.get("port")).intValue());
        assertEquals(3, ((Number) connection.get("database")).intValue());
        assertEquals(REDIS_PASSWORD, connection.get("password"));
    }

    /** 关闭缓存：只写开关（false）与后端，不写 Redis 连接项（也没写密码）。 */
    @Test
    void disabledCacheKeepsRedisConnectionOutOfConfigFile() throws Exception {
        mockMvc.perform(post("/api/setup/core")
                        .param("databaseType", "sqlite")
                        .param("databaseFile", "./data/wiki.db")
                        .param("adminUsername", "CacheAdmin")
                        .param("adminPassword", "secret123")
                        // 没有 cacheEnabled = 复选框未勾选（关闭）
                        .param("cacheBackend", "redis")
                        .param("cacheRedisHost", "redis.example.com")
                        .param("cacheRedisPort", "6380")
                        .param("cacheRedisDatabase", "3")
                        .param("cacheRedisPassword", REDIS_PASSWORD))
                .andExpect(jsonPath("$.code").value(200));

        assertTrue(!siteSettingsService.getBoolean(PageCacheService.ENABLED_KEY, true),
                "关闭缓存后站点设置里的开关应为 false");
        assertTrue(!pageCacheService.enabled(), "关闭后当前进程内不应启用缓存");

        mockMvc.perform(post("/api/setup/finish")).andExpect(jsonPath("$.code").value(200));
        String raw = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(raw.contains("enabled: false"), raw);
        assertTrue(raw.contains("backend: redis"), raw);

        Map<String, Object> yaml = loadYaml();
        Map<String, Object> cachePage = section(yaml, "wikikernel", "cache", "page");
        assertEquals(Boolean.FALSE, cachePage.get("enabled"));
        assertEquals("redis", cachePage.get("backend"));
        assertNull(cachePage.get("redis"), "关闭缓存时不应写 wikikernel.cache.page.redis");
        assertNull(yaml.get("spring"), "关闭缓存时不应写 spring.data.redis.*");
        assertTrue(!raw.contains("redis.example.com"), "关闭缓存时不应写 Redis 主机");
        assertTrue(!raw.contains(REDIS_PASSWORD), "关闭缓存时不应写 Redis 密码");
    }

    /** 已安装后解锁 /init：预填上次保存的缓存值（开关勾选、后端选中、连接与键前缀回填）。 */
    @Test
    void initPrefillsSavedCacheValues() throws Exception {
        mockMvc.perform(post("/api/setup/core")
                        .param("databaseType", "sqlite")
                        .param("databaseFile", "./data/wiki.db")
                        .param("adminUsername", "CacheAdmin")
                        .param("adminPassword", "secret123")
                        .param("cacheEnabled", "true")
                        .param("cacheBackend", "auto")
                        .param("cacheRedisHost", "cache.internal")
                        .param("cacheRedisPort", "6390")
                        .param("cacheRedisDatabase", "2")
                        .param("cacheRedisPassword", REDIS_PASSWORD)
                        .param("cacheRedisKeyPrefix", "wiki-a"))
                .andExpect(jsonPath("$.code").value(200));
        MvcResult finishResult = mockMvc.perform(post("/api/setup/finish"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String key = JsonPath.read(finishResult.getResponse().getContentAsString(), "$.data.key");

        // 已安装：先解锁，再渲染
        MockHttpSession unlocked = new MockHttpSession();
        mockMvc.perform(get("/init").param("key", key).session(unlocked))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/init"));
        String html = mockMvc.perform(get("/init").session(unlocked))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("缓存（可选）")))
                .andExpect(content().string(containsString("name=\"cacheRedisHost\"")))
                .andReturn().getResponse().getContentAsString();

        assertTrue(tagOf(html, "setup-cache-enabled").contains("checked"),
                "已保存的开关应回填为勾选：" + tagOf(html, "setup-cache-enabled"));
        assertEquals("auto", selectedOption(html, "setup-cache-backend"));
        assertTrue(tagOf(html, "setup-cache-redis-host").contains("value=\"cache.internal\""),
                tagOf(html, "setup-cache-redis-host"));
        assertTrue(tagOf(html, "setup-cache-redis-port").contains("value=\"6390\""),
                tagOf(html, "setup-cache-redis-port"));
        assertTrue(tagOf(html, "setup-cache-redis-database").contains("value=\"2\""),
                tagOf(html, "setup-cache-redis-database"));
        assertTrue(tagOf(html, "setup-cache-redis-key-prefix").contains("value=\"wiki-a:\""),
                tagOf(html, "setup-cache-redis-key-prefix"));
        // 已有密码：不回显，只提示「留空 = 保持原密码」
        assertTrue(tagOf(html, "setup-cache-redis-password").contains("保持原密码"),
                tagOf(html, "setup-cache-redis-password"));
        assertTrue(!html.contains(REDIS_PASSWORD), "页面里不应出现明文密码");
    }

    // ==================== 校验 ====================

    /** 非法端口 / 库号 / 后端 / 键前缀一律 400 + 本地化错误，不静默忽略。 */
    @Test
    void invalidCacheValuesAreRejected() throws Exception {
        // 固定前置状态：先提交一次最小必填项（缓存关闭），这样后面的开关断言与执行顺序无关
        saveCoreWithoutCache();

        // 端口越界
        mockMvc.perform(coreRequest("zh").param("cacheRedisPort", "70000"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("端口不合法"));
        // 端口不是数字
        mockMvc.perform(coreRequest("zh").param("cacheRedisPort", "redis"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("端口不合法"));
        // 库号越界（Redis 默认 0-15）
        mockMvc.perform(coreRequest("zh").param("cacheRedisDatabase", "16"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Redis 库号不合法（只能是 0-15 的整数）"));
        mockMvc.perform(coreRequest("zh").param("cacheRedisDatabase", "-1"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Redis 库号不合法（只能是 0-15 的整数）"));
        // 后端必须是四个枚举之一
        mockMvc.perform(coreRequest("zh").param("cacheBackend", "memcached"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缓存后端不合法（只能是 auto / redis / memory / none）"));
        // 键前缀：不能含空白
        mockMvc.perform(coreRequest("zh").param("cacheRedisKeyPrefix", "wiki a"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Redis 键前缀不合法（最多 128 个字符，且不能含空白或控制字符）"));

        // 被拒的请求不应改动站点设置（开关仍是上一轮保存的 false）
        assertTrue(!siteSettingsService.getBoolean(PageCacheService.ENABLED_KEY, false),
                "非法值被拒时不应写入站点设置");
        assertTrue(!Files.exists(CONFIG), "被拒的请求不应写配置文件");
    }

    // ==================== 辅助 ====================

    /** 首次安装的必填项（数据库 + 初始账号）；缓存字段由调用方按需追加；语言用 ?uselang= 指定。 */
    private static MockHttpServletRequestBuilder coreRequest(String language) {
        return post("/api/setup/core")
                .param("uselang", language)
                .param("databaseType", "sqlite")
                .param("databaseFile", "./data/wiki.db")
                .param("adminUsername", "CacheAdmin")
                .param("adminPassword", "secret123");
    }

    /** 只提交首次安装必填项（不带任何缓存参数 = 缓存保持默认关闭）。 */
    private void saveCoreWithoutCache() throws Exception {
        mockMvc.perform(coreRequest("zh"))
                .andExpect(jsonPath("$.code").value(200));
    }

    private static Map<String, Object> loadYaml() throws IOException {
        Object loaded = new Yaml().load(Files.readString(CONFIG, StandardCharsets.UTF_8));
        assertTrue(loaded instanceof Map, "配置文件应是 YAML 映射");
        return castMap(loaded);
    }

    /** 逐层取嵌套配置（缺失时返回空 map，断言自行给出可读的失败信息）。 */
    private static Map<String, Object> section(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            current = castMap(current.get(key));
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 取某个 id 的元素起始标签（断言属性用，不依赖属性顺序）。 */
    private static String tagOf(String html, String id) {
        int index = html.indexOf("id=\"" + id + "\"");
        assertTrue(index >= 0, "页面里应有 id=" + id);
        int start = html.lastIndexOf('<', index);
        int end = html.indexOf('>', index);
        return html.substring(start, end + 1);
    }

    /** 某个下拉框当前选中的值（找不到返回空串）。 */
    private static String selectedOption(String html, String selectId) {
        int index = html.indexOf("id=\"" + selectId + "\"");
        assertTrue(index >= 0, "页面里应有 id=" + selectId);
        int start = html.lastIndexOf("<select", index);
        int end = html.indexOf("</select>", index);
        String select = html.substring(start, end);
        for (String option : select.split("<option")) {
            if (option.contains("selected")) {
                Matcher matcher = Pattern.compile("value=\"([^\"]*)\"").matcher(option);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return "";
    }
}
