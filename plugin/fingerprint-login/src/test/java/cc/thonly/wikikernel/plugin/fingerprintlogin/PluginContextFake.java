package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * 插件上下文替身（不启动 Spring / core）：记录 {@code onStart} 里注册的扩展点与日志。
 * <p>
 * 与 abuse-filter / moderation / ai-agent 插件测试里的同名替身一致（插件之间不能互相依赖，
 * 所以每个插件的测试各留一份）。
 */
public final class PluginContextFake implements PluginContext {

    public final Map<Class<?>, List<Extension>> registered = new LinkedHashMap<>();
    public final List<String> logs = new ArrayList<>();
    private final String pluginId;
    private final PluginConfig config;

    public PluginContextFake(String pluginId) {
        this(pluginId, FingerprintLoginFakes.emptyPluginConfig());
    }

    public PluginContextFake(String pluginId, PluginConfig config) {
        this.pluginId = pluginId;
        this.config = config;
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
        return config;
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
