package cc.thonly.wikikernel.plugin.aiagent.model;

import cc.thonly.wikikernel.plugin.aiagent.AiAgentMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置厂商目录的本地化契约：Java 里只有**消息键**（没有中文文案），
 * 10 家厂商 × label / hint × 中文 / 英文必须齐全；演示厂商（{@code type=mock}）
 * 用新名字说清「回复是固定示例，不是真实 AI」。
 */
class AiVendorCatalogTest {

    /** 目录里应有的 10 家厂商（顺序 = 界面顺序）；id 是配置标识，改名不改 id。 */
    private static final List<String> VENDOR_IDS = List.of("mock", "openai", "deepseek", "groq", "gemini",
            "anthropic", "openrouter", "siliconflow", "ollama", "custom");

    /** 旧标签：改名后不允许再出现在任何文案里。 */
    private static final String OLD_MOCK_LABEL = "Mock（内置演示）";

    private static Map<String, Map<String, String>> messages() {
        return new AiAgentMessages().messages();
    }

    @Test
    void catalogKeepsTenVendorsWhoseNamesAreMessageKeys() {
        assertEquals(VENDOR_IDS, AiVendorCatalog.ids());
        assertEquals(10, AiVendorCatalog.all().size());
        for (AiVendorCatalog.Vendor vendor : AiVendorCatalog.all()) {
            assertEquals("aiAgent.vendor." + vendor.id() + ".label", vendor.labelKey(), vendor.id());
            assertEquals("aiAgent.vendor." + vendor.id() + ".hint", vendor.hintKey(), vendor.id());
        }
    }

    @Test
    void everyVendorLabelAndHintExistsInChineseAndEnglish() {
        Map<String, String> zh = messages().get("zh");
        Map<String, String> en = messages().get("en");
        assertEquals(zh.keySet(), en.keySet(), "中英文键必须一一对应");

        List<String> missing = new ArrayList<>();
        for (AiVendorCatalog.Vendor vendor : AiVendorCatalog.all()) {
            for (String key : List.of(vendor.labelKey(), vendor.hintKey())) {
                if (zh.get(key) == null || zh.get(key).isBlank()) {
                    missing.add("zh:" + key);
                }
                if (en.get(key) == null || en.get(key).isBlank()) {
                    missing.add("en:" + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "厂商文案缺键：" + missing);
        // 每家 2 条（label / hint）→ 每种语言 20 条
        assertEquals(20, zh.keySet().stream().filter(key -> key.startsWith("aiAgent.vendor.")).count());
        assertEquals(20, en.keySet().stream().filter(key -> key.startsWith("aiAgent.vendor.")).count());
    }

    @Test
    void demoVendorIsMockAndExplainsThatItIsADemo() {
        List<String> demoIds = AiVendorCatalog.demoVendorIds();
        assertFalse(demoIds.isEmpty(), "演示厂商列表不能为空");
        assertTrue(demoIds.contains(AiVendorCatalog.MOCK_ID), "演示厂商必须包含 mock：" + demoIds);
        assertEquals(List.of("mock"), demoIds);
        assertEquals("mock", AiVendorCatalog.MOCK_ID, "id 是老配置里的标识，不能因为改名而改");
        assertEquals(AiVendorCatalog.DEMO_TYPE, AiVendorCatalog.byId("mock").orElseThrow().type());

        Map<String, String> zh = messages().get("zh");
        Map<String, String> en = messages().get("en");
        assertEquals("演示模式（固定回复）", zh.get("aiAgent.vendor.mock.label"));
        assertEquals("Demo (canned replies)", en.get("aiAgent.vendor.mock.label"));
        assertNotEquals(OLD_MOCK_LABEL, zh.get("aiAgent.vendor.mock.label"));

        // 提示要同时说清：离线演示、回复是固定示例、不是真实 AI、不需要密钥、不消耗额度、别当免费 API，
        // 以及真正对话的入口（参数设置 → AI 代理 / 管理员站点密钥页）
        String zhHint = zh.get("aiAgent.vendor.mock.hint");
        for (String expected : List.of("离线演示", "固定示例", "不是真实 AI", "不需要密钥", "不消耗任何额度",
                "免费 API", "特殊:参数设置", "特殊:AI代理?keys=1")) {
            assertTrue(zhHint.contains(expected), "中文提示应包含「" + expected + "」：" + zhHint);
        }
        String enHint = en.get("aiAgent.vendor.mock.hint");
        for (String expected : List.of("Offline demo", "canned examples", "not a real AI", "no API key",
                "spends no quota", "free API", "Special:Preferences", "Special:AIAgent?keys=1")) {
            assertTrue(enHint.contains(expected), "英文提示应包含「" + expected + "」：" + enHint);
        }
    }

    @Test
    void oldMockLabelIsGoneAndTheDemoLockTextsArePresent() {
        for (Map<String, String> bundle : messages().values()) {
            for (Map.Entry<String, String> entry : bundle.entrySet()) {
                assertFalse(entry.getValue().contains(OLD_MOCK_LABEL),
                        entry.getKey() + " 还在用旧标签：" + entry.getValue());
            }
            // 选中演示厂商时锁住输入用的两条文案
            assertFalse(isBlank(bundle.get("aiAgent.demoLockNotice")), "缺少演示锁提示");
            assertFalse(isBlank(bundle.get("aiAgent.demoLockPlaceholder")), "缺少演示锁占位符");
        }
        assertTrue(messages().get("zh").get("aiAgent.demoLockPlaceholder").contains("输入已锁定"));
        assertTrue(messages().get("en").get("aiAgent.demoLockPlaceholder").contains("input is locked"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
