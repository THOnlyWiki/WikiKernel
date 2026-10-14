package cc.thonly.wikikernel.plugin.notitle;

import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NoTitleSwitch}：开关名 / 别名与页面属性写入。
 */
class NoTitleSwitchTest {

    @Test
    void writesNotitleProperty() {
        NoTitleSwitch behavior = new NoTitleSwitch();
        FakeContext context = new FakeContext();
        behavior.apply(context);
        assertEquals("1", context.properties().get(NoTitleSwitch.PROPERTY));
    }

    @Test
    void supportsChineseAlias() {
        NoTitleSwitch behavior = new NoTitleSwitch();
        assertEquals("NOTITLE", behavior.name());
        assertTrue(behavior.aliases().contains("无标题"));
    }

    /** 最小渲染上下文实现（只测插件自身逻辑，不依赖 core）。 */
    private static final class FakeContext implements WikiRenderContext {

        private final Map<String, Object> store = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

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
}
