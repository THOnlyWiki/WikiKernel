package cc.thonly.wikikernel.plugin.templatedata;

import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.plugin.templatedata.hook.TemplateDataTag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateDataPlugin}：插件入口注册的扩展点与配置读取。
 */
class TemplateDataPluginTest {

    @Test
    void descriptorMatchesThePortedExtension() {
        TemplateDataPlugin plugin = new TemplateDataPlugin();
        assertEquals("template-data", plugin.descriptor().id());
        assertEquals("TemplateData", plugin.descriptor().name());
        assertEquals("0.1.2", plugin.descriptor().version());
    }

    @Test
    void onStartRegistersTagAndApiModule() {
        FakeContext context = new FakeContext(Map.of("content-language", "en", "generate-param-order", "true"));
        new TemplateDataPlugin().onStart(context);

        assertEquals(1, context.lookup(ParserTag.class).size());
        ParserTag tag = context.lookup(ParserTag.class).get(0);
        assertInstanceOf(TemplateDataTag.class, tag);
        assertEquals("templatedata", tag.name());

        assertEquals(1, context.lookup(ActionApiModule.class).size());
        assertEquals("templatedata", context.lookup(ActionApiModule.class).get(0).action());
    }

    @Test
    void configDefaultsToSystemLanguage() {
        TemplateDataConfig config = TemplateDataConfig.from(new FakeConfig(Map.of()));
        assertEquals(TemplateDataMessages.defaultContentLanguage(), config.contentLanguage());
        assertTrue(List.of("zh", "en").contains(config.contentLanguage()));
        assertEquals(false, config.generateParamOrder());

        TemplateDataConfig english = TemplateDataConfig.from(
                new FakeConfig(Map.of("content-language", "EN", "generate-param-order", "true")));
        assertEquals("en", english.contentLanguage());
        assertEquals(true, english.generateParamOrder());
    }

    /** 假插件配置（common 的 {@link PluginConfig}）。 */
    private static final class FakeConfig implements PluginConfig {

        private final Map<String, String> values;

        private FakeConfig(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public Map<String, String> asMap() {
            return Map.copyOf(values);
        }
    }

    /** 假插件上下文：只记录注册的扩展点。 */
    private static final class FakeContext implements PluginContext {

        private final PluginConfig config;
        private final List<Extension> extensions = new ArrayList<>();

        private FakeContext(Map<String, String> configValues) {
            this.config = new FakeConfig(new LinkedHashMap<>(configValues));
        }

        @Override
        public String pluginId() {
            return TemplateDataPlugin.PLUGIN_ID;
        }

        @Override
        public Path dataDir() {
            return Path.of("build", "test-working-dir");
        }

        @Override
        public PluginConfig config() {
            return config;
        }

        @Override
        public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
            assertInstanceOf(extensionPoint, extension);
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
            return System.getLogger("template-data-test");
        }
    }
}
