package cc.thonly.wikikernel.plugin.flashplayer;

import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.StoredMediaFile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlashPlayerTag}：外链 / 本地文件解析、参数属性、回退链接、缺 file 错误、开关关闭退化。
 * <p>
 * 文案测试替身按**插件自己的消息包**（{@link FlashPlayerMessages}，{@code i18n/messages*.properties}）
 * 解析，等价于 core 的 {@code PluginMessageSource}（core 优先 → 插件消息 → 翻译键），
 * 断言里的中文就是插件资源文件里的实际文案。
 */
class FlashPlayerTagTest {

    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> texts = new FlashPlayerMessages().messages()
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

    private static final FileStore FILES = fileName -> {
        if ("动画演示.swf".equals(fileName)) {
            return Optional.of(new StoredMediaFile("动画演示.swf", "/files/动画演示.swf",
                    "/wiki/文件:动画演示.swf", false, "other"));
        }
        return Optional.empty();
    };

    @Test
    void externalUrlRendersPlayerPlaceholder() {
        FlashPlayerTag tag = new FlashPlayerTag(config(true), FILES, MESSAGES);
        String html = tag.render(call("""
                file=https://example.com/movie.swf
                width=320
                height=240
                autoplay=false
                loop=true
                bgcolor=#123456
                flashvars=a=1&b=两
                quality=low
                scale=noborder
                """, "演示动画"));

        assertTrue(html.contains("class=\"flash-player\" data-flash-player=\"1\""), html);
        assertTrue(html.contains("data-flash-src=\"https://example.com/movie.swf\""), html);
        assertTrue(html.contains("data-flash-width=\"320\"") && html.contains("data-flash-height=\"240\""), html);
        assertTrue(html.contains("data-flash-autoplay=\"false\""), html);
        assertTrue(html.contains("data-flash-loop=\"true\""), html);
        assertTrue(html.contains("data-flash-bgcolor=\"#123456\""), html);
        assertTrue(html.contains("data-flash-flashvars=\"a=1&amp;b=两\""), html);
        assertTrue(html.contains("data-flash-quality=\"low\"") && html.contains("data-flash-scale=\"noborder\""), html);
        assertTrue(html.contains("class=\"flash-player-fallback\" href=\"https://example.com/movie.swf\""), html);
        assertTrue(html.contains("演示动画"), html);
        assertTrue(html.contains("style=\"width:320px;max-width:100%\""), html);
    }

    @Test
    void wikiFileResolvesToUrlAndPage() {
        FlashPlayerTag tag = new FlashPlayerTag(config(true), FILES, MESSAGES);
        String html = tag.render(call("file=[[文件:动画演示.swf]]\n", ""));
        assertTrue(html.contains("data-flash-src=\"/files/动画演示.swf\""), html);
        assertTrue(html.contains("href=\"/wiki/文件:动画演示.swf\""), html);
    }

    @Test
    void missingFileShowsRedLink() {
        FlashPlayerTag tag = new FlashPlayerTag(config(true), FILES, MESSAGES);
        String html = tag.render(call("file=不存在.swf\n", ""));
        assertTrue(html.contains("wiki-error"), html);
        assertTrue(html.contains("文件不存在"), html);
        assertTrue(html.contains("class=\"new\""), html);
    }

    @Test
    void missingFileParameterShowsError() {
        FlashPlayerTag tag = new FlashPlayerTag(config(true), FILES, MESSAGES);
        String html = tag.render(call("width=320\n", ""));
        assertTrue(html.contains("wiki-error"), html);
        assertTrue(html.contains("缺少 file 参数"), html);
    }

    @Test
    void invalidWidthFallsBackToDefault() {
        FlashPlayerTag tag = new FlashPlayerTag(config(true), FILES, MESSAGES);
        String html = tag.render(call("file=https://example.com/a.swf\nwidth=abc\n", ""));
        assertTrue(html.contains("data-flash-width=\"550\""), html);
    }

    @Test
    void disabledFeatureDegradesToFileLink() {
        FlashPlayerTag tag = new FlashPlayerTag(config(false), FILES, MESSAGES);
        String html = tag.render(call("file=[[文件:动画演示.swf]]\n", ""));
        assertTrue(html.contains("flash-player-disabled"), html);
        assertTrue(html.contains("（Flash 播放器未启用）"), html);
        assertTrue(html.contains("href=\"/wiki/文件:动画演示.swf\""), html);
        assertFalse(html.contains("data-flash-player=\"1\""), html);
    }

    // ==================== 测试替身 ====================

    private static ParserTagCall call(String attributeText, String inner) {
        Map<String, String> attributes = FlashPlayerTag.parseAttributes(attributeText);
        return new ParserTagCall() {
            @Override
            public WikiRenderContext context() {
                return new FakeContext();
            }

            @Override
            public String tag() {
                return "flash-player";
            }

            @Override
            public Map<String, String> attributes() {
                return attributes;
            }

            @Override
            public String body() {
                return inner;
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
