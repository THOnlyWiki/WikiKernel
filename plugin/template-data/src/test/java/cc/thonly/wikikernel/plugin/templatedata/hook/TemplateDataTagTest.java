package cc.thonly.wikikernel.plugin.templatedata.hook;

import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataConfig;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.service.TemplateDataBlob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateDataTag}：{@code <templatedata>} 的页面属性写入、错误框、文档子页面与样式片段。
 */
class TemplateDataTagTest {

    private static final TemplateDataConfig CONFIG = new TemplateDataConfig("zh", false);
    private static final TemplateDataMessages ZH = TemplateDataMessages.of("zh");

    @Test
    void tagNameAndRawBodyBehaviour() {
        TemplateDataTag tag = new TemplateDataTag(CONFIG, ZH);
        assertEquals("templatedata", tag.name());
        assertEquals(List.of("templatedata"), tag.names());
        // 标签内容不参与模板展开（JSON 原样交给插件）
        assertFalse(tag.expandBody());
    }

    @Test
    void validJsonStoresNormalizedPropertyAndRendersDocTable() {
        FakeContext context = new FakeContext("模板:问候");
        String html = tag().render(new FakeCall(context, """
                {"description":"打招呼","params":{"1":{"type":"string/line","required":true}}}
                """));

        String stored = context.property(TemplateDataBlob.PROPERTY_NAME, null);
        assertEquals(TemplateDataBlob.parse("""
                {"description":"打招呼","params":{"1":{"type":"string/line","required":true}}}
                """, "zh").getJsonForDatabase(), stored);
        assertTrue(stored.contains("\"type\":\"line\""), stored);
        assertTrue(html.contains("mw-templatedata-doc-wrap"), html);
        assertTrue(html.contains("打招呼"), html);
        // 样式片段只输出一次
        assertTrue(html.startsWith("<style>"), html);
    }

    @Test
    void invalidJsonShowsErrorBoxAndDoesNotStoreProperty() {
        FakeContext context = new FakeContext("模板:问候");
        String html = tag().render(new FakeCall(context, "{\"params\":[]}"));

        assertTrue(html.equals("<div class=\"errorbox\"><p>属性“params”的类型应为“object”。</p></div>"), html);
        assertNull(context.property(TemplateDataBlob.PROPERTY_NAME, null));
        assertTrue(context.properties().isEmpty());
    }

    @Test
    void missingJsonShowsParseError() {
        FakeContext context = new FakeContext("模板:问候");
        String html = tag().render(new FakeCall(context, ""));
        assertTrue(html.contains("JSON内有语法错误。"), html);
        assertNull(context.property(TemplateDataBlob.PROPERTY_NAME, null));
    }

    @Test
    void docSubpagesDoNotStoreProperty() {
        // 中文 /doc 与 /文档、以及大小写变体都不写属性（对齐原版 T54448）
        for (String title : List.of("模板:问候/文档", "模板:问候/doc", "Template:Greeting/Doc")) {
            FakeContext context = new FakeContext(title);
            tag().render(new FakeCall(context, "{\"params\":{}}"));
            assertNull(context.property(TemplateDataBlob.PROPERTY_NAME, null), title);
        }
    }

    @Test
    void otherSubpagesAndRootPagesStillStoreProperty() {
        for (String title : List.of("模板:问候", "模板:问候/沙盒", "模板:问候/doc2")) {
            FakeContext context = new FakeContext(title);
            tag().render(new FakeCall(context, "{\"params\":{}}"));
            assertTrue(context.properties().containsKey(TemplateDataBlob.PROPERTY_NAME), title);
        }
    }

    @Test
    void stylesAreEmittedOnlyOncePerRender() {
        FakeContext context = new FakeContext("模板:问候");
        TemplateDataTag tag = tag();
        String first = tag.render(new FakeCall(context, "{\"params\":{}}"));
        String second = tag.render(new FakeCall(context, "{\"params\":{}}"));

        assertTrue(first.contains("<style>"), first);
        assertFalse(second.contains("<style>"), second);
        assertTrue(second.startsWith("<section"), second);
    }

    @Test
    void englishLocaleRendersEnglishTable() {
        FakeContext context = new FakeContext("Template:Greeting");
        String html = new TemplateDataTag(CONFIG, TemplateDataMessages.of("en"))
                .render(new FakeCall(context, "{\"params\":{}}"));
        assertTrue(html.contains("No description."), html);
        assertTrue(html.contains("No parameters specified"), html);
    }

    private static TemplateDataTag tag() {
        return new TemplateDataTag(CONFIG, ZH);
    }

    /** 假渲染上下文：只实现标签需要的页面标题 / 变量存储 / 页面属性。 */
    private static final class FakeContext implements WikiRenderContext {

        private final String pageTitle;
        private final Map<String, Object> store = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        private FakeContext(String pageTitle) {
            this.pageTitle = pageTitle;
        }

        @Override
        public String pageTitle() {
            return pageTitle;
        }

        @Override
        public Map<String, Object> store() {
            return store;
        }

        @Override
        public void setProperty(String name, String value) {
            properties.put(name, value);
        }

        @Override
        public String property(String name, String defaultValue) {
            return properties.getOrDefault(name, defaultValue);
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(properties);
        }

        @Override
        public String deferWikitext(Supplier<String> resolver) {
            return resolver.get();
        }
    }

    /** 假标签调用：标签内容是原始 JSON，不展开（expandBody=false）。 */
    private static final class FakeCall implements ParserTagCall {

        private final WikiRenderContext context;
        private final String body;

        private FakeCall(WikiRenderContext context, String body) {
            this.context = context;
            this.body = body;
        }

        @Override
        public WikiRenderContext context() {
            return context;
        }

        @Override
        public String tag() {
            return TemplateDataTag.TAG_NAME;
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
    }
}
