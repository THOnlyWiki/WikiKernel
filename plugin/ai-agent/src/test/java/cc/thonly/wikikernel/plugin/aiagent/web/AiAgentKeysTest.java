package cc.thonly.wikikernel.plugin.aiagent.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentFakes;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商密钥（用户级 / 站点级）：
 * 读写与优先级判断走 {@code common.service.AiConfig} 桥（插件不直接碰数据库与站点设置），
 * 站点级（含「允许普通用户使用」）只有管理员 / 行政员能改，界面只回传尾号掩码。
 */
class AiAgentKeysTest {

    private AiAgentFakes.Fixture fixture() {
        return new AiAgentFakes.Fixture();
    }

    @Test
    void keyPriorityIsUserThenSiteThenYamlAndMaskKeepsOnlyTail() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        // 管理员：站点级与用户级都能改（下面会验证普通用户的 403）
        Viewer user = fixture.admin("密钥用户");
        currentUser.viewer = user;
        AiKeysApiController controller = new AiKeysApiController(fixture.registry, fixture.aiConfig,
                fixture.support, currentUser);

        // 1）yaml 里写死（最低优先级）
        fixture.aiConfig.yamlEntries.put("deepseek", new AiConfigEntry("deepseek", "sk-yaml-0000").entry());
        assertEquals("yaml", fixture.aiConfig.sourceOf(user.id(), "deepseek"));
        assertEquals("sk-yaml-0000", fixture.aiConfig.yaml("deepseek").apiKey());
        assertTrue(fixture.aiConfig.yamlHasKey("deepseek"));

        // 2）站点密钥盖过 yaml
        AjaxResult<Map<String, Object>> siteSaved = controller.save("site", "deepseek",
                "sk-site-1234abcd", "https://api.deepseek.com/v1", "deepseek-chat", "", null);
        assertEquals(200, siteSaved.getCode());
        assertEquals(Boolean.TRUE, siteSaved.getData().get("configured"));
        assertEquals("••••abcd", siteSaved.getData().get("mask"), "只回传尾号掩码");
        assertFalse(String.valueOf(siteSaved.getData()).contains("sk-site-1234abcd"), "密钥绝不回传");
        assertEquals("site", fixture.aiConfig.sourceOf(user.id(), "deepseek"));

        // 3）用户密钥优先级最高，并且只写用户自己的那一条
        AjaxResult<Map<String, Object>> userSaved = controller.save("user", "deepseek", "sk-user-9999",
                null, null, "socks5://127.0.0.1:10808", null);
        assertEquals(200, userSaved.getCode());
        assertEquals("user", userSaved.getData().get("source"));
        assertEquals("sk-user-9999", fixture.aiConfig.user(user.id(), "deepseek").apiKey());
        assertEquals("socks5://127.0.0.1:10808", fixture.aiConfig.user(user.id(), "deepseek").proxy());
        assertEquals("sk-site-1234abcd", fixture.aiConfig.site("deepseek").apiKey(), "站点密钥不受影响");

        // 厂商解析用最终生效的密钥 + 自定义模型 / 代理
        AiProviderRegistry.Selection selection = fixture.registry.selection(user.id(), "deepseek").orElseThrow();
        assertEquals("user", selection.source());
        assertEquals("deepseek-chat", selection.model());

        // 4）清除用户密钥 → 回落到站点密钥
        AjaxResult<Map<String, Object>> cleared = controller.save("user", "deepseek", null, null, null, null, "1");
        assertEquals(200, cleared.getCode());
        assertFalse(fixture.aiConfig.user(user.id(), "deepseek").configured());
        assertEquals("site", fixture.aiConfig.sourceOf(user.id(), "deepseek"));
        assertTrue(String.valueOf(cleared.getMessage()).contains("已清除"), String.valueOf(cleared.getMessage()));
    }

    @Test
    void onlyManagersMaySaveSiteKeysOrToggleAllowNormalUsers() {
        AiAgentFakes.Fixture fixture = fixture();
        AiAgentFakes.FakeCurrentUser currentUser = new AiAgentFakes.FakeCurrentUser();
        AiKeysApiController controller = new AiKeysApiController(fixture.registry, fixture.aiConfig,
                fixture.support, currentUser);

        // 普通登录用户：可以填自己的密钥，不能改站点密钥 / 站点开关
        currentUser.viewer = fixture.user("普通用户");
        assertEquals(200, controller.save("user", "deepseek", "sk-mine", null, null, null, null).getCode());
        AjaxResult<Map<String, Object>> siteDenied = controller.save("site", "deepseek", "sk-x",
                null, null, null, null);
        assertEquals(403, siteDenied.getCode());
        assertTrue(String.valueOf(siteDenied.getMessage()).contains("只有管理员"),
                String.valueOf(siteDenied.getMessage()));
        assertEquals(403, controller.allowNormal("1").getCode());
        assertFalse(fixture.aiConfig.allowNormalUsers);

        // 管理员：站点密钥与「允许普通用户使用」都可以改（写的是同一个站点设置键）
        currentUser.viewer = fixture.admin("管理员甲");
        assertEquals(200, controller.save("site", "groq", "gsk-abcdef", null, null, null, null).getCode());
        assertEquals("gsk-abcdef", fixture.aiConfig.site("groq").apiKey());
        assertEquals(200, controller.allowNormal("1").getCode());
        assertTrue(fixture.aiConfig.allowNormalUsers);
        assertTrue(fixture.service.allowed(fixture.user("任意登录用户")));

        // 未登录 → 401；厂商不存在 → 400
        currentUser.viewer = Viewer.ANONYMOUS;
        assertEquals(401, controller.save("user", "deepseek", "sk-x", null, null, null, null).getCode());
        currentUser.viewer = fixture.admin("管理员乙");
        assertEquals(400, controller.save("site", "不存在的厂商", "sk-x", null, null, null, null).getCode());
    }

    @Test
    void settingsRowsNeverExposeRawKeys() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer user = fixture.user("密钥展示用户");
        fixture.aiConfig.userEntries.put("openai", new AiConfigEntry("openai", "sk-user-7777").entry());
        fixture.aiConfig.siteEntries.put("openai", new AiConfigEntry("openai", "sk-site-8888").entry());
        fixture.aiConfig.yamlEntries.put("groq", new AiConfigEntry("groq", "gsk-yaml-9999").entry());

        List<Map<String, Object>> rows = fixture.registry.settingsRows(user.id());

        Map<String, Object> openai = rows.stream().filter(row -> "openai".equals(row.get("id"))).findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, openai.get("userConfigured"));
        assertEquals("••••7777", openai.get("userMask"));
        assertEquals("••••8888", openai.get("siteMask"));
        assertEquals("user", openai.get("source"));
        Map<String, Object> groq = rows.stream().filter(row -> "groq".equals(row.get("id"))).findFirst()
                .orElseThrow();
        assertEquals("yaml", groq.get("source"));
        assertEquals(Boolean.TRUE, groq.get("yamlHasKey"));
        assertFalse(String.valueOf(rows).contains("sk-user-7777"), "行数据不得含密钥明文");
        assertFalse(String.valueOf(rows).contains("gsk-yaml-9999"), "行数据不得含密钥明文");
    }

    @Test
    void mockProviderIsAlwaysAvailableAndOthersNeedKeys() {
        AiAgentFakes.Fixture fixture = fixture();
        Viewer user = fixture.user("厂商可用性用户");

        // Mock 恒可用（新站也能跑通流程）；其余厂商没有密钥时不出现
        assertEquals(List.of("mock"), fixture.registry.selections(user.id()).stream()
                .map(AiProviderRegistry.Selection::vendorId).toList());
        assertEquals("mock", fixture.registry.defaultVendorId(user.id()));
        assertFalse(fixture.registry.selection(user.id(), "openai").isPresent());

        fixture.aiConfig.siteEntries.put("openai", new AiConfigEntry("openai", "sk-openai").entry());
        assertTrue(fixture.registry.selection(user.id(), "openai").isPresent());
        assertEquals("site", fixture.registry.selection(user.id(), "openai").orElseThrow().source());

        // yaml 里的自定义厂商（不在内置目录里的 id）也能用
        fixture.aiConfig.yamlEntries.put("my-local", new AiConfig.Entry("my-local", "", "http://127.0.0.1:8080/v1",
                "local-model", "", "openai", "本地模型", null, null));
        assertTrue(fixture.registry.selections(user.id()).stream()
                .anyMatch(selection -> "my-local".equals(selection.vendorId())));
    }

    /** 组装一条厂商配置的小助手。 */
    private record AiConfigEntry(String providerId, String apiKey) {

        AiConfig.Entry entry() {
            return new AiConfig.Entry(providerId, apiKey, "", "", "");
        }
    }
}
