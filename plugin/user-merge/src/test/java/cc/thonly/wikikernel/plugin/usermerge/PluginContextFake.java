package cc.thonly.wikikernel.plugin.usermerge;

import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * 插件上下文替身（不启动 Spring / core）：记录 {@code onStart} 里注册的扩展点与日志，
 * 并提供把 {@code @Autowired} 字段灌进插件入口类的反射助手。
 */
public final class PluginContextFake implements PluginContext {

    public final Map<Class<?>, List<Extension>> registered = new LinkedHashMap<>();
    public final List<String> logs = new ArrayList<>();
    private final String pluginId;

    public PluginContextFake(String pluginId) {
        this.pluginId = pluginId;
    }

    /** 把值灌进插件入口类的私有字段（模拟 Spring 的字段注入）。 */
    public static void inject(Object target, String field, Object value) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("测试注入失败: " + field, exception);
        }
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Path dataDir() {
        return Path.of("build", "test-plugin-data");
    }

    @Override
    public PluginConfig config() {
        return new PluginConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public Map<String, String> asMap() {
                return Map.of();
            }
        };
    }

    @Override
    public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
        registered.computeIfAbsent(extensionPoint, key -> new ArrayList<>()).add(extension);
    }

    @Override
    public <T extends Extension> List<T> lookup(Class<T> extensionPoint) {
        return extensions(extensionPoint);
    }

    /** 已注册的某类扩展点（按注册顺序）。 */
    public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
        List<T> result = new ArrayList<>();
        for (Extension extension : registered.getOrDefault(extensionPoint, List.of())) {
            result.add(extensionPoint.cast(extension));
        }
        return result;
    }

    /** 是否注册过某类扩展点。 */
    public boolean has(Class<? extends Extension> extensionPoint) {
        return !registered.getOrDefault(extensionPoint, List.of()).isEmpty();
    }

    @Override
    public System.Logger logger() {
        return new RecordingLogger(pluginId, logs);
    }

    /** 只记录消息的 {@link System.Logger} 替身。 */
    private static final class RecordingLogger implements System.Logger {

        private final String name;
        private final List<String> logs;

        private RecordingLogger(String name, List<String> logs) {
            this.name = name;
            this.logs = logs;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            logs.add(message);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            logs.add(format);
        }
    }
}