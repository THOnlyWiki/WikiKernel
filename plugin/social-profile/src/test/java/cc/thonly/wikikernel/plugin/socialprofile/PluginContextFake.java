package cc.thonly.wikikernel.plugin.socialprofile;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.Extension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件上下文的测试替身：给插件读「插件配置」（{@code wikikernel.plugins.settings.social-profile.*}）
 * 与注册扩展点用（与生产环境的 {@code SimplePluginContext} / core 的插件子容器语义一致）。
 */
public final class PluginContextFake implements PluginContext {

    private final String pluginId;
    private final Path dataDir;
    private final Map<String, String> settings;
    private final Map<Class<?>, List<Object>> extensions = new LinkedHashMap<>();

    public PluginContextFake(String pluginId, Map<String, String> settings) {
        this.pluginId = pluginId;
        this.dataDir = Path.of("build", "test-plugin-data", pluginId);
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /** 带一份插件配置的替身（键是不带前缀的短键，如 {@code board.pageSize}）。 */
    public static PluginContextFake create(Map<String, String> settings) {
        return new PluginContextFake("social-profile", settings);
    }

    /** 空配置。 */
    public static PluginContextFake create() {
        return create(Map.of());
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public PluginConfig config() {
        return new PluginConfig() {
            @Override
            public String get(String key, String defaultValue) {
                String value = settings.get(key);
                return value == null ? defaultValue : value;
            }

            @Override
            public Map<String, String> asMap() {
                return settings;
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
        extensions.computeIfAbsent(extensionPoint, key -> new ArrayList<>()).add(extension);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Extension> List<T> lookup(Class<T> extensionPoint) {
        List<Object> registered = extensions.get(extensionPoint);
        if (registered == null) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (Object extension : registered) {
            result.add((T) extension);
        }
        return List.copyOf(result);
    }

    @Override
    public System.Logger logger() {
        return System.getLogger("test-plugin-" + pluginId);
    }
}
