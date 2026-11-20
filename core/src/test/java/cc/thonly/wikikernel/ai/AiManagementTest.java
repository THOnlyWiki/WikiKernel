package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.ai.AiConfig;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "wikikernel.run-path=./ai-test-run"})
@AutoConfigureMockMvc
class AiManagementTest {
    @Autowired MockMvc mvc;
    @Autowired WikiUserMapper users;
    @Autowired PermissionService permissions;
    @Autowired AiConfig config;
    @Autowired PluginDatabase db;
    @Autowired cc.thonly.wikikernel.common.service.SiteConfig settings;
    @Autowired cc.thonly.wikikernel.common.ai.AiProviderFactory factory;
    @Autowired AiCoreBootstrap bootstrap;

    private MockHttpSession session(boolean admin) {
        WikiUser user = new WikiUser(); user.setUsername("AI" + System.nanoTime()); user.setPassword("unused"); user.setEmail(""); users.insert(user);
        permissions.replaceFor(user.getId(), List.of(admin ? PermissionService.GROUP_ADMIN : PermissionService.GROUP_USER));
        var session = new MockHttpSession(); session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy()); return session;
    }

    @Test
    void coreOwnsManagementAndCredentialsWithoutPlugins() throws Exception {
        mvc.perform(get("/api/ai-management")).andExpect(status().isForbidden());
        var reader = session(false);
        mvc.perform(post("/api/ai-management/provider").session(reader).header("Origin", "http://localhost").header("Host", "localhost")
                .param("id", "attempt").param("model", "model")).andExpect(status().isForbidden());
        var admin = session(true);
        mvc.perform(get("/wiki/特殊:AI管理").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ai-management")));
        mvc.perform(post("/api/ai-management/provider").session(admin).header("Origin", "http://localhost").header("Host", "localhost")
                .param("id", "news-test").param("label", "News test").param("type", "openai")
                .param("baseUrl", "http://127.0.0.1:12345/v1").param("allowPrivate", "true")
                .param("model", "test-model").param("apiKey", "site-secret-1234")).andExpect(status().isOk());
        assertEquals("site-secret-1234", config.site("news-test").apiKey());
        String stored = db.queryOne("SELECT setting_value FROM wiki_setting WHERE setting_key = ?", "ai.provider.news-test.key")
                .orElseThrow().string("setting_value");
        assertTrue(stored.startsWith("encrypted:v1:")); assertFalse(stored.contains("site-secret"));
        mvc.perform(get("/api/ai-management").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("site-secret-1234"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("news-test")));
        mvc.perform(post("/api/ai-management/scene").session(admin).header("Origin", "http://localhost").header("Host", "localhost")
                .param("id", "news").param("provider", "news-test").param("enabled", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void personalKeysRemainSeparateAndCannotChangeSiteCredentials() throws Exception {
        var reader = session(false);
        mvc.perform(post("/api/ai-keys").session(reader).header("Origin", "http://localhost").header("Host", "localhost")
                .param("scope", "site").param("provider", "deepseek").param("apiKey", "forbidden"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/ai-keys").session(reader).header("Origin", "http://localhost").header("Host", "localhost")
                .param("scope", "user").param("provider", "deepseek").param("apiKey", "personal-secret-5678"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.mask").value("••••5678"));
        var rows = db.query("SELECT api_key FROM wiki_user_ai_key");
        assertTrue(rows.stream().anyMatch(row -> row.string("api_key").startsWith("encrypted:v1:")));
        assertTrue(rows.stream().noneMatch(row -> row.string("api_key").contains("personal-secret")));
    }

    @Test
    void oldActivitySettingsMigrateWithoutOverwritingAnotherProfile() {
        config.saveSite("thonly-legacy", "existing-key", "https://example.com/v1", "existing-model", "", false);
        settings.set("thonly-ai.base-url", "http://127.0.0.1:12345/v1");
        settings.set("thonly-ai.model", "activity-model");
        settings.set("thonly-ai.api-key", "activity-key");
        settings.set("thonly-ai.allow-private", "true");
        settings.set("thonly-ai.enabled", "true");
        settings.set("ai.migration.v1", "false");
        bootstrap.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        String migrated = settings.get("ai.scene.activity.provider", "");
        assertNotEquals("thonly-legacy", migrated);
        assertEquals("existing-key", config.site("thonly-legacy").apiKey());
        assertEquals("activity-key", config.site(migrated).apiKey());
        assertEquals("activity-model", config.site(migrated).model());
        assertEquals("", settings.get("thonly-ai.api-key", ""));
    }

    @Test
    void networkRequestsShareTheCoreDailyBudgetAndUsageLedger() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        settings.set("ai.provider.quota-test.private", "true");
        long used = db.queryOne("SELECT COUNT(*) AS total FROM wiki_ai_call").orElseThrow().longValue("total", 0);
        settings.set("ai.daily-call-limit", String.valueOf(used + 1));
        try {
            var provider = factory.create("quota-test", new cc.thonly.wikikernel.common.ai.AiProviderConfig("openai",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model", "Quota", 0.2, 100, ""));
            var input = List.of(cc.thonly.wikikernel.common.ai.AiMessage.user("private test input"));
            assertEquals("OK", provider.complete(input));
            assertThrows(cc.thonly.wikikernel.common.ai.AiException.class, () -> provider.complete(input));
            var row = db.queryOne("SELECT outcome, input_chars, output_chars FROM wiki_ai_call ORDER BY id DESC LIMIT 1").orElseThrow();
            assertEquals("SUCCESS", row.string("outcome"));
            assertEquals(2, row.longValue("output_chars", 0));
            assertEquals(18, row.longValue("input_chars", 0));
        } finally { settings.set("ai.daily-call-limit", "100"); server.stop(0); }
    }
}
