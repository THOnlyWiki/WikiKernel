package cc.thonly.wikikernel.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旧配置兼容：没有 {@code wikikernel.setup.key} 的配置文件（旧版向导生成 / 手动删除密钥）——
 * {@code /init} 保持开放；再次「写入配置并重启」时生成并写入随机密钥，之后 /init 才需要密钥。
 * <p>
 * 配置文件路径优先取系统属性 {@code wk.setup.test.file}（Gradle 会指到测试沙箱目录，
 * 避免测试工作目录改动影响本用例）；IDE 直接跑测试时回退到相对路径。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.setup.mode=auto",
        "wikikernel.setup.file=${wk.setup.test.file:build/setup-migrate-LocalSettings.yaml}",
        "wikikernel.setup.restart-enabled=false"
})
@AutoConfigureMockMvc
class SetupMigrationTest {

    private static final Path CONFIG = Path.of(System.getProperty("wk.setup.test.file",
            "build/setup-migrate-LocalSettings.yaml"));

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    @Test
    void configWithoutKeyStaysOpenAndGeneratesKeyOnFinish() throws Exception {
        Files.createDirectories(CONFIG.toAbsolutePath().getParent());
        Files.writeString(CONFIG, """
                wikikernel:
                  database: {type: sqlite, file: ./data/wiki.db}
                """, StandardCharsets.UTF_8);

        // 没有密钥：/init 直接可用（不再锁死）
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"setup-finish\"")))
                .andExpect(content().string(not(containsString("id=\"setup-unlock-form\""))));

        // 再次写入配置：生成随机密钥写进 YAML，已有配置保留
        MvcResult result = mockMvc.perform(post("/api/setup/finish"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.key").isNotEmpty())
                .andReturn();
        String key = JsonPath.read(result.getResponse().getContentAsString(), "$.data.key");
        String yaml = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("type: sqlite"), "已有数据库配置应保留");
        assertTrue(yaml.contains(key), "密钥应写入 YAML");

        // 之后 /init 需要密钥
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"setup-unlock-form\"")));
    }
}
