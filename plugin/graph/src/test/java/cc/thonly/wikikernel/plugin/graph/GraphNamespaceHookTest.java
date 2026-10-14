package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphNamespaceHook}（图表命名空间整页图）与 {@link GraphHeadProvider}（Vega 脚本注入）。
 */
class GraphNamespaceHookTest {

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

    private static GraphSupport support(boolean enabled) {
        return new GraphSupport(config(enabled), MESSAGES);
    }

    @Test
    void claimsGraphNamespacePage() {
        String html = new GraphNamespaceHook(support(true))
                .renderBody(context("图表:Vega演示图", "{\"mark\": \"bar\"}"));
        assertTrue(html != null && html.contains("mw-graph"), String.valueOf(html));
    }

    @Test
    void invalidPageSpecShowsError() {
        String html = new GraphNamespaceHook(support(true)).renderBody(context("图表:坏的图", "不是规范"));
        assertTrue(html != null && html.contains("wiki-error"), String.valueOf(html));
    }

    @Test
    void ignoresOtherTitlesAndDisabledFeature() {
        assertNull(new GraphNamespaceHook(support(true)).renderBody(context("首页", "{\"mark\":\"bar\"}")));
        assertNull(new GraphNamespaceHook(support(false)).renderBody(context("图表:Vega演示图", "{\"mark\":\"bar\"}")));
    }

    @Test
    void headProviderInjectsVegaAndGraphScript() {
        GraphHeadProvider provider = new GraphHeadProvider(support(true));
        assertTrue(provider.siteWide(), "编辑预览等页面也需要图形脚本");
        List<String> items = provider.headItems(head());
        String joined = String.join("\n", items);
        assertTrue(joined.contains("/vendor/vega/vega.min.js"), joined);
        assertTrue(joined.contains("/vendor/vega/vega-lite.min.js"), joined);
        assertTrue(joined.contains("/vendor/vega/vega-embed.min.js"), joined);
        assertTrue(joined.contains("/plugins/graph/graph.js"), joined);
        assertTrue(joined.contains("WIKI_CONFIG.graph = true"), joined);

        assertEquals(List.of(), new GraphHeadProvider(support(false)).headItems(head()));
    }

    // ==================== 测试替身 ====================

    private static PageDisplayContext context(String title, String content) {
        return new PageDisplayContext() {
            @Override
            public long pageId() {
                return 7;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public String content() {
                return content;
            }

            @Override
            public boolean oldRevision() {
                return false;
            }

            @Override
            public String pageUrl() {
                return "/wiki/" + title;
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Viewer viewer() {
                return Viewer.ANONYMOUS;
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }
        };
    }

    private static HeadContext head() {
        return new HeadContext() {
            @Override
            public long pageId() {
                return 7;
            }

            @Override
            public String pageTitle() {
                return "图表:Vega演示图";
            }

            @Override
            public String path() {
                return "/wiki/图表:Vega演示图";
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
                return "http://localhost:18080";
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
        };
    }
}
