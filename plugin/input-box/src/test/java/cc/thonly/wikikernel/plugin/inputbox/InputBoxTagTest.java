package cc.thonly.wikikernel.plugin.inputbox;

import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputBoxTag}：表单生成（创建 / 搜索、prefix/preload/editintro、缺省与转义）与开关关闭行为。
 * <p>
 * 表单按钮的文字用 core 的通用键 {@code common.search} / {@code special.createPage}，
 * 测试替身按下表解析（等价于 core 的 {@code PluginMessageSource}：core 消息优先 → 插件消息 → 翻译键）。
 */
class InputBoxTagTest {

    /** core 的 {@code messages.properties} 里本插件用到的两个键。 */
    private static final Map<String, String> CORE_MESSAGES = Map.of(
            "common.search", "搜索",
            "special.createPage", "创建页面");

    private static final MessageSource MESSAGES = new MessageSource() {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return CORE_MESSAGES.getOrDefault(code, defaultMessage);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return CORE_MESSAGES.getOrDefault(code, code);
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

    @Test
    void rendersCreateFormWithOptions() {
        InputBoxTag tag = new InputBoxTag(config(true), MESSAGES);
        String html = tag.render(call("""
                type=create
                prefix=帮助:
                preload=模板:草稿
                editintro=说明
                default=默认值
                placeholder=写点什么
                width=40
                id=box1
                buttonlabel=开始
                """));

        assertTrue(html.contains("<form class=\"inputbox\" method=\"get\" action=\"/wiki/特殊:创建页面\""), html);
        assertTrue(html.contains("id=\"box1\""), html);
        assertTrue(html.contains("data-prefix=\"帮助:\""), html);
        assertTrue(html.contains("data-preload=\"模板:草稿\""), html);
        assertTrue(html.contains("data-editintro=\"说明\""), html);
        assertTrue(html.contains("name=\"pagename\""), html);
        assertTrue(html.contains("value=\"默认值\""), html);
        assertTrue(html.contains("placeholder=\"写点什么\""), html);
        assertTrue(html.contains("size=\"40\""), html);
        assertTrue(html.contains(">开始</button>"), html);
    }

    @Test
    void rendersSearchFormAndFallsBackToCoreMessages() {
        InputBoxTag tag = new InputBoxTag(config(true), MESSAGES);
        String search = tag.render(call("type=search\n"));
        assertTrue(search.contains("action=\"/wiki/特殊:搜索\""), search);
        assertTrue(search.contains("name=\"q\""), search);
        assertFalse(search.contains("data-prefix"), search);
        assertTrue(search.contains(">搜索</button>"), search);

        String fulltext = tag.render(call("type=fulltext\n"));
        assertTrue(fulltext.contains("action=\"/wiki/特殊:搜索\""), fulltext);
    }

    @Test
    void disabledFeatureShowsTagAsText() {
        InputBoxTag tag = new InputBoxTag(config(false), MESSAGES);
        String html = tag.render(call("type=create\nprefix=<b>\n"));
        assertTrue(html.contains("&lt;inputbox&gt;"), html);
        assertTrue(html.contains("&lt;/inputbox&gt;"), html);
        assertFalse(html.contains("<form"), html);
    }

    @Test
    void escapesAttributeValues() {
        InputBoxTag tag = new InputBoxTag(config(true), MESSAGES);
        String html = tag.render(call("default=\"a\" onmouseover=alert(1)\n"));
        assertTrue(html.contains("value=\"&quot;a&quot; onmouseover=alert(1)\""), html);
    }

    // ==================== 测试替身 ====================

    private static ParserTagCall call(String body) {
        return new ParserTagCall() {
            private final WikiRenderContext context = new FakeContext();

            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public String tag() {
                return "inputbox";
            }

            @Override
            public Map<String, String> attributes() {
                return Map.of();
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public String expand(String wikitext) {
                return wikitext;
            }
        };
    }

    private static final class FakeContext implements WikiRenderContext {

        private final Map<String, Object> store = new LinkedHashMap<>();

        @Override
        public String pageTitle() {
            return "测试页";
        }

        @Override
        public Map<String, Object> store() {
            return store;
        }

        @Override
        public void setProperty(String name, String value) {
        }

        @Override
        public String property(String name, String defaultValue) {
            return defaultValue;
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public String deferWikitext(Supplier<String> resolver) {
            return resolver.get();
        }
    }
}
