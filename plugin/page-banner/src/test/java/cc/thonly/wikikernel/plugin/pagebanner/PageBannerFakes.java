package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.StoredMediaFile;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 页面横幅测试替身：站点设置（{@link SiteConfig}）、插件配置（{@link PluginConfig}）、
 * 媒体文件（{@link FileStore}）、渲染上下文（{@link WikiRenderContext}）与页面显示上下文（{@link PageDisplayContext}）。
 */
public final class PageBannerFakes {

    private PageBannerFakes() {
    }

    /** 站点设置桩：键 → 值（未设置的键返回调用方给的默认值，与 core 的站点设置语义一致）。 */
    public static final class FakeSiteConfig implements SiteConfig {

        private final Map<String, String> values;

        public FakeSiteConfig(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "1".equals(normalized)
                    || "on".equals(normalized) || "yes".equals(normalized);
        }

        @Override
        public void set(String key, String value) {
            throw new UnsupportedOperationException("测试替身不支持写站点设置：" + key);
        }
    }

    /** 媒体文件桩：文件名（不区分大小写，支持「文件:」前缀）→ 已存文件。 */
    public static final class FakeFileStore implements FileStore {

        private final Map<String, StoredMediaFile> files = new LinkedHashMap<>();

        public FakeFileStore(StoredMediaFile... stored) {
            for (StoredMediaFile file : stored) {
                files.put(key(file.name()), file);
            }
        }

        @Override
        public Optional<StoredMediaFile> find(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(files.get(key(fileName)));
        }

        private static String key(String fileName) {
            String name = fileName.trim();
            if (name.regionMatches(true, 0, "文件:", 0, 3)) {
                name = name.substring(3);
            } else if (name.regionMatches(true, 0, "File:", 0, 5)) {
                name = name.substring(5);
            }
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /** 图片文件。 */
    public static StoredMediaFile image(String name) {
        return new StoredMediaFile(name, "/files/" + name, "/wiki/文件:" + name, true, "image");
    }

    /** 非图片文件（音频 / 视频等：不显示横幅）。 */
    public static StoredMediaFile video(String name) {
        return new StoredMediaFile(name, "/files/" + name, "/wiki/文件:" + name, false, "video");
    }

    public static PageBannerSettings settings(Map<String, String> values) {
        return new PageBannerSettings(new FakeSiteConfig(values));
    }

    /**
     * 站点设置 + 插件自己的配置
     * （{@code application.yaml} 的 {@code wikikernel.plugins.settings.page-banner.*}，短键）。
     */
    public static PageBannerSettings settings(Map<String, String> values, Map<String, String> pluginValues) {
        return new PageBannerSettings(new FakeSiteConfig(values), new MapPluginConfig(pluginValues));
    }

    public static PageBannerRenderer renderer(Map<String, String> values, StoredMediaFile... files) {
        return new PageBannerRenderer(settings(values), new FakeFileStore(files));
    }

    /** 带插件配置的渲染器（插件配置短键优先于站点设置键）。 */
    public static PageBannerRenderer renderer(Map<String, String> values, Map<String, String> pluginValues,
                                              StoredMediaFile... files) {
        return new PageBannerRenderer(settings(values, pluginValues), new FakeFileStore(files));
    }

    /** 插件装配用的上下文（等同 core 的 {@code SimplePluginContext}，带插件私有的短键配置）。 */
    public static SimplePluginContext pluginContext(Map<String, String> pluginValues) {
        return SimplePluginContext.create("page-banner", Path.of("build", "test-working-dir"), pluginValues);
    }

    /** 键值对快捷构造：{@code config("features.page-banner", "false")}。 */
    public static Map<String, String> config(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    /** 渲染上下文桩：只实现页面属性（插件写属性的断言用）。 */
    public static final class FakeRenderContext implements WikiRenderContext {

        private final String pageTitle;
        private final Map<String, Object> store = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        public FakeRenderContext() {
            this("测试页");
        }

        public FakeRenderContext(String pageTitle) {
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
            properties.put(name, value == null ? "" : value);
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
            return "";
        }
    }

    /** 解析器函数调用桩：参数按顶层 {@code |} 拆分（与 core 一致）。 */
    public static ParserFunctionCall functionCall(WikiRenderContext context, String arguments) {
        List<String> args = arguments.isEmpty()
                ? List.of()
                : Arrays.stream(arguments.split("\\|", -1)).toList();
        return new ParserFunctionCall() {
            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public List<String> args() {
                return List.copyOf(args);
            }

            @Override
            public String expand(String wikitext) {
                return wikitext == null ? "" : wikitext;
            }
        };
    }

    /** 解析器标签调用桩。 */
    public static ParserTagCall tagCall(WikiRenderContext context, String body) {
        return new ParserTagCall() {
            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public String tag() {
                return "exclude-banner-page";
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
                return wikitext == null ? "" : wikitext;
            }
        };
    }

    /** 页面显示上下文桩（PageDisplayHook 用）。 */
    public static PageDisplayContext displayContext(String title, Map<String, String> properties) {
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
                return "";
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
                return properties == null ? Map.of() : Map.copyOf(properties);
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

    /** 记录注册扩展点的插件上下文桩（插件装配断言用）。 */
    public static final class FakePluginContext implements PluginContext {

        private final List<Extension> extensions = new ArrayList<>();

        @Override
        public String pluginId() {
            return "page-banner";
        }

        @Override
        public Path dataDir() {
            return Path.of("build", "test-working-dir");
        }

        @Override
        public PluginConfig config() {
            return new MapPluginConfig(Map.of());
        }

        @Override
        public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
            extensions.add(extension);
        }

        @Override
        public <T extends Extension> List<T> lookup(Class<T> extensionPoint) {
            List<T> result = new ArrayList<>();
            for (Extension extension : extensions) {
                if (extensionPoint.isInstance(extension)) {
                    result.add(extensionPoint.cast(extension));
                }
            }
            return result;
        }

        @Override
        public System.Logger logger() {
            return System.getLogger("page-banner-test");
        }
    }
}
