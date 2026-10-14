package cc.thonly.wikikernel.common.plugin.support;

import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginConstants;
import cc.thonly.wikikernel.common.plugin.PluginContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PluginContext 的默认实现。
 * <p>
 * core 通过它加载插件；插件自己的 main 方法也可以用它独立启动，便于脱离 core 调试。
 */
public final class SimplePluginContext implements PluginContext {

    private final String pluginId;
    private final Path dataDir;
    private final PluginConfig config;
    private final ExtensionRegistry registry = new ExtensionRegistry();
    private final System.Logger logger;

    public SimplePluginContext(String pluginId, Path dataDir, PluginConfig config) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.config = config == null ? new MapPluginConfig(Map.of()) : config;
        this.logger = System.getLogger(PluginConstants.LOGGER_PREFIX + pluginId);
    }

    /** 供插件 main 方法使用的快捷构造。 */
    public static SimplePluginContext create(String pluginId, Path dataDir, Map<String, String> settings) {
        return new SimplePluginContext(pluginId, dataDir, new MapPluginConfig(settings));
    }

    @Override
    public String pluginId() {
        return this.pluginId;
    }

    @Override
    public Path dataDir() {
        return this.dataDir;
    }

    @Override
    public PluginConfig config() {
        return this.config;
    }

    @Override
    public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
        this.registry.register(extensionPoint, extension);
    }

    @Override
    public <T extends Extension> List<T> lookup(Class<T> extensionPoint) {
        return this.registry.lookup(extensionPoint);
    }

    @Override
    public System.Logger logger() {
        return this.logger;
    }
}
