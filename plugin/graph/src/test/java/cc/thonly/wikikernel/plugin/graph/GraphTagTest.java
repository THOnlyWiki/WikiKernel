package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphTag} / {@link GraphSupport}：Vega 规范占位、属性（title / width / height）、
 * 无效规范提示、开关关闭时按原文转义，以及 JSON 转义。
 */
class GraphTagTest {

    private static final String VEGA = """
            {
              "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
              "description": "a < b & c > d",
              "data": {"values": [{"a": "A", "b": 28}]},
              "mark": "bar",
              "encoding": {"x": {"field": "a", "type": "nominal"}}
            }
            """;

    /** 测试用消息源：直接读插件自己 jar 里的中文消息包（验证 i18n/messages.properties 能取到文案）。 */
    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> zh =
                new GraphPlugin.GraphMessages().messages().getOrDefault("zh", Map.of());

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            String value = zh.get(code);
            if (value == null) {
                return defaultMessage == null ? code : defaultMessage;
            }
            return args == null || args.length == 0 ? value : new MessageFormat(value).format(args);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return getMessage(code, args, null, locale);
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

    private static GraphTag tag(boolean enabled) {
        return new GraphTag(new GraphSupport(config(enabled), MESSAGES));
    }

    @Test
    void rendersVegaSpecIntoFigure() {
        String html = tag(true).render(call(Map.of("title", "每月销量", "width", "420"), VEGA));
        assertTrue(html.startsWith("<figure class=\"mw-graph\""), html);
        assertTrue(html.contains("style=\"width:420px;\""), html);
        assertTrue(html.contains("<div class=\"mw-graph-plot\">"), html);
        assertTrue(html.contains("（图形需要 JavaScript）"), html);
        assertTrue(html.contains("<script type=\"application/json\" class=\"mw-graph-spec\">"), html);
        assertFalse(html.contains("&lt;"), "规范里的 < 应转成 \\u003c，而不是 HTML 实体：" + html);
        assertTrue(html.contains("\\u003c") && html.contains("\\u003e") && html.contains("\\u0026"),
                "JSON 里的 < > & 应转义：" + html);
        assertTrue(html.contains("<figcaption class=\"mw-graph-caption\">每月销量</figcaption>"), html);
    }

    @Test
    void invalidSpecShowsLocalizedError() {
        String empty = tag(true).render(call(Map.of(), "   "));
        assertTrue(empty.contains("class=\"wiki-error\""), empty);
        assertTrue(empty.contains("graph：内容为空或不是有效的 Vega"), empty);
        assertFalse(empty.contains("mw-graph-spec"), empty);

        String notJson = tag(true).render(call(Map.of(), "这不是 JSON"));
        assertTrue(notJson.contains("class=\"wiki-error\""), notJson);
    }

    @Test
    void disabledFeatureShowsRawTag() {
        String html = tag(false).render(call(Map.of("width", "300"), VEGA));
        assertTrue(html.contains("&lt;graph"), html);
        assertTrue(html.contains("&lt;/graph&gt;"), html);
        assertFalse(html.contains("<figure"), html);
    }

    @Test
    void parsesAttributeForms() {
        Map<String, String> parsed = GraphSupport.parseAttributes("title=\"a b\" width=300 height='200' data-x");
        assertEquals("a b", parsed.get("title"));
        assertEquals("300", parsed.get("width"));
        assertEquals("200", parsed.get("height"));
        assertEquals("", parsed.get("data-x"));
    }

    @Test
    void detectsGraphNamespaceTitles() {
        assertTrue(GraphSupport.isGraphTitle("图表:Vega演示图"));
        assertTrue(GraphSupport.isGraphTitle("Graph:Demo"));
        assertFalse(GraphSupport.isGraphTitle("图表讨论:Vega演示图"));
        assertFalse(GraphSupport.isGraphTitle("首页"));
        assertFalse(GraphSupport.isGraphTitle("图表:"));
        assertNull(new GraphSupport(config(true), MESSAGES).placeholder("", ""));
    }

    // ==================== 测试替身 ====================

    private static ParserTagCall call(Map<String, String> attributes, String body) {
        return new ParserTagCall() {
            private final WikiRenderContext context = new FakeContext();

            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public String tag() {
                return "graph";
            }

            @Override
            public Map<String, String> attributes() {
                return attributes;
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
            return "图形演示页";
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
