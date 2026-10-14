package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MathTag}：公式占位（行内 / 块级 / chem）、空白压缩、转义、空公式与开关关闭行为。
 * <p>
 * 文案测试替身按**插件自己的消息包**（{@link MathMessages}，{@code i18n/messages*.properties}）解析，
 * 等价于 core 的 {@code PluginMessageSource}（core 优先 → 插件消息 → 翻译键），
 * 断言里的中文就是插件资源文件里的实际文案。
 */
class MathTagTest {

    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> texts = new MathMessages().messages()
                .getOrDefault("zh", Map.of());

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return texts.getOrDefault(code, defaultMessage);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return texts.getOrDefault(code, code);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return resolvable.getDefaultMessage();
        }
    };

    private static SiteConfig config(boolean enabled) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return enabled;
            }
        };
    }

    private static final class FakeCall implements ParserTagCall {

        private final Map<String, String> attributes;

        FakeCall(Map<String, String> attributes) {
            this.attributes = attributes;
        }

        @Override
        public WikiRenderContext context() {
            return null;
        }

        @Override
        public String tag() {
            return "math";
        }

        @Override
        public Map<String, String> attributes() {
            return attributes;
        }

        @Override
        public String body() {
            return body;
        }

        private String body = "";

        FakeCall body(String value) {
            this.body = value;
            return this;
        }

        @Override
        public String expand(String wikitext) {
            return wikitext;
        }
    }

    @Test
    void inlineMathRendersPlaceholder() {
        MathTag tag = new MathTag(config(true), MESSAGES, false);
        String html = tag.render(new FakeCall(Map.of()).body("E = mc^2"));
        assertTrue(html.contains("class=\"mw-math\" data-tex=\"E = mc^2\" data-display=\"inline\""), html);
        assertTrue(html.contains("class=\"mw-math-fallback\">E = mc^2</span>"), html);
        assertFalse(html.contains("data-chem"), html);
    }

    @Test
    void blockMathCollapsesWhitespace() {
        MathTag tag = new MathTag(config(true), MESSAGES, false);
        String html = tag.render(new FakeCall(Map.of("display", "block"))
                .body("\n  \\int_0^1 x^2\\,dx = \\frac{1}{3}\n"));
        assertTrue(html.contains("class=\"mw-math mw-math-block\""), html);
        assertTrue(html.contains("data-display=\"block\""), html);
        assertTrue(html.contains("data-tex=\"\\int_0^1 x^2\\,dx = \\frac{1}{3}\""), html);
    }

    @Test
    void chemRendersMhchemFallback() {
        MathTag tag = new MathTag(config(true), MESSAGES, true);
        String html = tag.render(new FakeCall(Map.of()).body("H2O"));
        assertTrue(html.contains("data-chem=\"1\""), html);
        assertTrue(html.contains("data-tex=\"H2O\""), html);
        assertTrue(html.contains("\\ce{H2O}"), html);
    }

    @Test
    void emptyFormulaShowsError() {
        MathTag tag = new MathTag(config(true), MESSAGES, false);
        String html = tag.render(new FakeCall(Map.of()).body("   "));
        assertTrue(html.contains("wiki-error"), html);
        assertTrue(html.contains("公式内容为空"), html);
    }

    @Test
    void disabledFeatureShowsTagAsText() {
        MathTag tag = new MathTag(config(false), MESSAGES, false);
        String html = tag.render(new FakeCall(Map.of("display", "block")).body("E = mc^2"));
        assertTrue(html.contains("&lt;math display=&quot;block&quot;&gt;"), html);
        assertTrue(html.contains("&lt;/math&gt;"), html);
        assertFalse(html.contains("mw-math"), html);
    }

    @Test
    void escapesTexInAttributesAndFallback() {
        MathTag tag = new MathTag(config(true), MESSAGES, false);
        String html = tag.render(new FakeCall(Map.of()).body("a < b & \"c\""));
        assertTrue(html.contains("data-tex=\"a &lt; b &amp; &quot;c&quot;\""), html);
        assertTrue(html.contains("&lt;"), html);
    }

    @Test
    void parsesMixedAttributeQuotes() {
        Map<String, String> attributes = MathTag.parseAttributes("display='block' class=\"a\" data-x=1");
        assertTrue("block".equals(attributes.get("display")), attributes.toString());
        assertTrue("a".equals(attributes.get("class")), attributes.toString());
        assertTrue("1".equals(attributes.get("data-x")), attributes.toString());
    }
}
