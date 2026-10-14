package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentFakes;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商显示名 / 提示的解析链路：{@code AiVendorCatalog} 只给消息键，
 * {@link AiProviderRegistry} 按**当前界面语言**解析 ——
 * {@link AiProviderRegistry.Selection#label()}（对话界面的厂商下拉）与
 * {@code settingsRows}（站点密钥面板 / 参数设置 → AI 代理 的 label + hint）。
 * 缺键时回退成**厂商 id**，不回退中文。
 */
class AiProviderRegistryLabelTest {

    @Test
    void selectionAndSettingsRowsUseTheCurrentInterfaceLanguage() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        Viewer user = fixture.user("厂商本地化用户");
        assertEquals(List.of("mock"), fixture.registry.demoVendorIds(), "演示厂商 id 列表来自目录（type=mock）");
        try {
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals("演示模式（固定回复）",
                    fixture.registry.selection(user.id(), "mock").orElseThrow().label());
            Map<String, Object> chinese = row(fixture, user, "mock");
            assertEquals("演示模式（固定回复）", chinese.get("label"));
            assertTrue(String.valueOf(chinese.get("hint")).contains("不是真实 AI"), String.valueOf(chinese.get("hint")));

            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertEquals("Demo (canned replies)",
                    fixture.registry.selection(user.id(), "mock").orElseThrow().label());
            Map<String, Object> english = row(fixture, user, "ollama");
            assertEquals("Ollama (local models)", english.get("label"));
            assertTrue(String.valueOf(english.get("hint")).contains("fully offline"),
                    String.valueOf(english.get("hint")));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void missingVendorMessageFallsBackToTheVendorIdNeverToChinese() {
        AiAgentFakes.Fixture fixture = new AiAgentFakes.Fixture();
        AiProviderRegistry registry = new AiProviderRegistry(fixture.aiConfig, new ObjectMapper(),
                new AiAgentSupport(fixture.siteConfig, new PartialMessageSource()));

        assertEquals("OpenAI (localized)", registry.vendorLabel("openai"), "有译文时用译文");
        assertEquals("mock", registry.vendorLabel("mock"), "缺键时回退成厂商 id");
        assertEquals("my-local", registry.vendorLabel("my-local"), "目录外的 id 原样返回");
        assertEquals("mock", row(registry, "mock").get("label"));
        // 提示漏翻时按项目约定回显键名（消息源行为），也不会回退中文
        assertEquals("aiAgent.vendor.mock.hint", row(registry, "mock").get("hint"));
    }

    private static Map<String, Object> row(AiAgentFakes.Fixture fixture, Viewer user, String vendorId) {
        return row(fixture.registry, user.id(), vendorId);
    }

    private static Map<String, Object> row(AiProviderRegistry registry, String vendorId) {
        return row(registry, 0, vendorId);
    }

    private static Map<String, Object> row(AiProviderRegistry registry, long userId, String vendorId) {
        return registry.settingsRows(userId).stream()
                .filter(item -> vendorId.equals(item.get("id")))
                .findFirst().orElseThrow();
    }

    /** 只认识一部分键的消息源（core 的规则：缺键时用调用方给的默认值；默认值是中文才换成键名）。 */
    private static final class PartialMessageSource implements MessageSource {

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return "aiAgent.vendor.openai.label".equals(code) ? "OpenAI (localized)" : defaultMessage;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return getMessage(code, args, null, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            String[] codes = resolvable.getCodes();
            return getMessage(codes == null || codes.length == 0 ? "" : codes[0], resolvable.getArguments(),
                    resolvable.getDefaultMessage(), locale);
        }
    }
}
