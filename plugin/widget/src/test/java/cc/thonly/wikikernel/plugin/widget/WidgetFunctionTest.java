package cc.thonly.wikikernel.plugin.widget;

import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WidgetFunction} / {@link WidgetRenderer}：{@code {{#widget:…}}} 的参数构造
 * （点号嵌套 / 重复键 / 布尔）、前缀解析、可信块输出、开关与错误提示。
 */
class WidgetFunctionTest {

    /** 测试用消息源：直接读插件自己 jar 里的中文消息包（验证 i18n/messages.properties 能取到文案）。 */
    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> zh =
                new WidgetPlugin.WidgetMessages().messages().getOrDefault("zh", Map.of());

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

    /** 内存页面桥：标题 → 正文。 */
    private static WikiPageStore store(Map<String, String> pages) {
        return new WikiPageStore() {
            @Override
            public Optional<PageSnapshot> byId(long pageId) {
                return Optional.empty();
            }

            @Override
            public Optional<PageSnapshot> byTitle(String title) {
                return pages.containsKey(title)
                        ? Optional.of(new PageSnapshot(1, title, false, false, 0, null)) : Optional.empty();
            }

            @Override
            public Optional<PageSnapshot> visibleByTitle(String title) {
                return byTitle(title);
            }

            @Override
            public Optional<String> content(long pageId) {
                return pages.values().stream().findFirst();
            }

            @Override
            public List<PageSnapshot> contentPages() {
                return List.of();
            }
        };
    }

    @Test
    void rendersWidgetWithNestedAndRepeatedParams() {
        WikiPageStore pages = store(Map.of("微件:示例",
                "<div id=\"<!--{$id}-->\"><!--{foreach from=$items item=x}--><!--{$x}--><!--{/foreach}--></div>"));
        WidgetRenderer renderer = new WidgetRenderer(pages, config(true), MESSAGES,
                WidgetSupport.parsePrefixes(null));
        WidgetFunction function = new WidgetFunction(renderer);

        // 重复键成列表（items=一|items=二）+ 命名参数
        String html = function.invoke(call("微件:示例", "id=abc", "items=一", "items=二"));
        assertEquals("<div id=\"abc\">一二</div>", html);
    }

    @Test
    void shortNameUsesConfiguredPrefixes() {
        WikiPageStore pages = store(Map.of("Widget:示例", "ok"));
        WidgetRenderer renderer = new WidgetRenderer(pages, config(true), MESSAGES,
                WidgetSupport.parsePrefixes("Widget:"));
        assertEquals(List.of("Widget:"), renderer.prefixes());
        assertEquals("ok", new WidgetFunction(renderer).invoke(call("示例")));
    }

    @Test
    void unknownWidgetAndDisabledFeatureShowErrors() {
        WidgetRenderer enabled = new WidgetRenderer(store(Map.of()), config(true), MESSAGES,
                WidgetSupport.parsePrefixes(null));
        String notFound = new WidgetFunction(enabled).invoke(call("没有这个"));
        assertTrue(notFound.contains("找不到微件"), notFound);

        WidgetRenderer disabled = new WidgetRenderer(store(Map.of("微件:示例", "x")), config(false), MESSAGES,
                WidgetSupport.parsePrefixes(null));
        String off = new WidgetFunction(disabled).invoke(call("示例"));
        assertTrue(off.contains("微件功能未启用"), off);
    }

    @Test
    void missingNameIsReported() {
        WidgetRenderer renderer = new WidgetRenderer(store(Map.of()), config(true), MESSAGES,
                WidgetSupport.parsePrefixes(null));
        assertTrue(new WidgetFunction(renderer).invoke(call("  ")).contains("未指定微件名称"));
    }

    @Test
    void boolParamAndFalseValue() {
        WikiPageStore pages = store(Map.of("微件:开关",
                "<!--{if $on}-->开<!--{else}-->关<!--{/if}--><!--{if $off}-->X<!--{else}-->无<!--{/if}-->"));
        WidgetRenderer renderer = new WidgetRenderer(pages, config(true), MESSAGES,
                WidgetSupport.parsePrefixes(null));
        // 无 = 的参数 = true；false 转布尔假
        assertEquals("开无", new WidgetFunction(renderer).invoke(call("微件:开关", "on", "off=false")));    }

    @Test
    void templateCacheIsReusedAndInvalidatedByContent() {
        MutableStore store = new MutableStore("版本一");
        WidgetRenderer renderer = new WidgetRenderer(store, config(true), MESSAGES, WidgetSupport.parsePrefixes(null));
        WidgetFunction function = new WidgetFunction(renderer);

        assertEquals("版本一", function.invoke(call("微件:缓存")));
        assertEquals("版本一", function.invoke(call("微件:缓存")));
        assertEquals(1, renderer.cachedTemplates());

        // 内容变了：按内容做 key 的缓存自动失效（重新编译）
        store.update("版本二");
        assertEquals("版本二", function.invoke(call("微件:缓存")));
        assertEquals(2, renderer.cachedTemplates());
    }

    /** 可改内容的页面桥（缓存失效测试用）。 */
    private static final class MutableStore implements WikiPageStore {

        private String content;

        MutableStore(String content) {
            this.content = content;
        }

        void update(String value) {
            this.content = value;
        }

        @Override
        public Optional<PageSnapshot> byId(long pageId) {
            return Optional.empty();
        }

        @Override
        public Optional<PageSnapshot> byTitle(String title) {
            return Optional.of(new PageSnapshot(1, title, false, false, 0, null));
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.of(content);
        }

        @Override
        public List<PageSnapshot> contentPages() {
            return List.of();
        }
    }

    @Test
    void parsesPrefixList() {
        assertEquals(List.of("微件:", "Widget:"), WidgetSupport.parsePrefixes(null));
        assertEquals(List.of("微件:", "Widget:"), WidgetSupport.parsePrefixes("微件,Widget:"));
        assertEquals(List.of("Gadget:"), WidgetSupport.parsePrefixes(" Gadget: "));
    }

    // ==================== 测试替身 ====================

    private static ParserFunctionCall call(String... args) {
        List<String> list = List.of(args);
        return new ParserFunctionCall() {
            private final WikiRenderContext context = new FakeContext();

            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public List<String> args() {
                return list;
            }

            @Override
            public String expand(String wikitext) {
                return wikitext;
            }

            @Override
            public String trustedHtml(String html) {
                return html;
            }
        };
    }

    private static final class FakeContext implements WikiRenderContext {

        private final Map<String, Object> store = new LinkedHashMap<>();

        @Override
        public String pageTitle() {
            return "微件演示页";
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
