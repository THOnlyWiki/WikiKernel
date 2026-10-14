package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORS 配置构建（{@link CorsConfig#configurationOf} 的去空白 / 通配 + 凭据转换 / 默认值）。 */
class CorsConfigTest {

    @Test
    void inactiveWithoutOriginOrWhenDisabled() {
        CorsProperties properties = new CorsProperties();

        // 开关打开但没配来源 → 不生效
        properties.setEnabled(true);
        assertFalse(CorsConfig.isActive(properties));

        // 配了来源（允许前后空格）→ 生效
        properties.setAllowedOrigins(List.of("  https://example.com  "));
        assertTrue(CorsConfig.isActive(properties));

        // 开关关闭 → 不生效
        properties.setEnabled(false);
        assertFalse(CorsConfig.isActive(properties));
    }

    @Test
    void buildsConfigurationFromProperties() {
        CorsProperties properties = new CorsProperties();
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of("https://example.com"));
        properties.setAllowedOriginPatterns(List.of(" ", "https://*.example.org"));

        CorsConfiguration configuration = CorsConfig.configurationOf(properties);

        assertEquals(List.of("https://example.com"), configuration.getAllowedOrigins());
        assertEquals(List.of("https://*.example.org"), configuration.getAllowedOriginPatterns());
        assertTrue(configuration.getAllowedMethods().contains("POST"));
        assertEquals(List.of("*"), configuration.getAllowedHeaders());
        assertEquals(3600L, configuration.getMaxAge().longValue());
        assertFalse(Boolean.TRUE.equals(configuration.getAllowCredentials()));
    }

    @Test
    void wildcardWithCredentialsFallsBackToPatterns() {
        CorsProperties properties = new CorsProperties();
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of("*"));
        properties.setAllowCredentials(true);

        CorsConfiguration configuration = CorsConfig.configurationOf(properties);

        // 浏览器规范不允许 credentials + 精确 "*"：转成来源模式（按请求 Origin 回显）
        assertTrue(configuration.getAllowedOrigins() == null || configuration.getAllowedOrigins().isEmpty());
        assertEquals(List.of("*"), configuration.getAllowedOriginPatterns());
        assertTrue(Boolean.TRUE.equals(configuration.getAllowCredentials()));
    }
}
