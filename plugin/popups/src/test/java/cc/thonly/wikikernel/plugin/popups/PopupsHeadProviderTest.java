package cc.thonly.wikikernel.plugin.popups;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PopupsHeadProvider} 与随插件分发的脚本 / 样式资源：
 * 功能开关控制注入；脚本只绑定正文区链接（侧栏等界面区域不预览）。
 * <p>
 * 浮层文案的测试替身按**插件自己的消息包**（{@link PopupsMessages}，{@code i18n/messages*.properties}）
 * 解析，等价于 core 的 {@code PluginMessageSource}（core 优先 → 插件消息 → 翻译键）。
 */
class PopupsHeadProviderTest {

    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> texts = new PopupsMessages().messages()
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

    @Test
    void injectsAssetsWhenEnabled() {
        PopupsHeadProvider provider = new PopupsHeadProvider(config(true), MESSAGES);
        List<String> items = provider.headItems(new FakeHeadContext());
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/popups/popups.css")), items.toString());
        assertTrue(items.stream().anyMatch(item -> item.contains("/plugins/popups/popups.js")), items.toString());
        // 浮层文案随 head 注入（站点级注入：只有站点级片段才在 core 的 WIKI_MESSAGES 之后）
        assertTrue(provider.siteWide(), "浮层文案要注入 window.WIKI_MESSAGES，必须在站点级片段里");
        assertTrue(items.stream().anyMatch(item -> item.contains("WIKI_MESSAGES")
                && item.contains("popupsGoToPage:") && item.contains("popupsEmptyExtract:")), items.toString());
    }

    @Test
    void injectsNothingWhenDisabled() {
        PopupsHeadProvider provider = new PopupsHeadProvider(config(false), MESSAGES);
        assertTrue(provider.headItems(new FakeHeadContext()).isEmpty());
    }

    @Test
    void scriptBindsContentAreaOnly() throws Exception {
        String script = resource("/static/plugins/popups/popups.js");
        assertTrue(script.contains("#bodyContent a[href^=\"/wiki/\"]"), "预览应只绑定正文区链接");
        assertFalse(script.contains("#wiki-panel"), "侧栏等界面区域不应触发预览");
        assertTrue(script.contains("/api/page-preview"), "数据来源应为通用预览 API");
        // 浮层文案来自 window.WIKI_MESSAGES，脚本里不再写死文案
        assertTrue(script.contains("window.WIKI_MESSAGES"), "脚本应读取注入的文案");
        assertTrue(script.contains("MESSAGES.popupsGoToPage"), script);
    }

    @Test
    void stylesAreShipped() throws Exception {
        String css = resource("/static/plugins/popups/popups.css");
        assertTrue(css.contains(".mw-popup"), css);
        assertTrue(css.contains(".mw-popup-thumb"), css);
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = PopupsHeadProviderTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "缺少资源: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class FakeHeadContext implements HeadContext {

        @Override
        public long pageId() {
            return 1;
        }

        @Override
        public String pageTitle() {
            return "测试页";
        }

        @Override
        public String path() {
            return "/wiki/测试页";
        }

        @Override
        public Map<String, String> query() {
            return Map.of();
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public String siteName() {
            return "WikiKernel";
        }

        @Override
        public String baseUrl() {
            return "http://localhost";
        }

        @Override
        public String fileUrl(String fileName) {
            return "";
        }

        @Override
        public int fileWidth(String fileName) {
            return 0;
        }

        @Override
        public int fileHeight(String fileName) {
            return 0;
        }
    }
}
