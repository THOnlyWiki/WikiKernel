package cc.thonly.wikikernel.common.plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * 插件运行上下文，由 core（或插件自己的 main 方法）提供。
 * <p>
 * 插件通过它注册扩展点、读取自己的配置、写自己的数据目录。
 */
public interface PluginContext {

    /** 当前插件 id。 */
    String pluginId();

    /** 插件专属数据目录（已确保存在），用于缓存、索引等。 */
    Path dataDir();

    /** 当前插件的私有配置。 */
    PluginConfig config();

    /**
     * 注册扩展点实现，供 core 或其他插件查询。
     *
     * @param extensionPoint common 中定义的扩展点接口
     * @param extension      实现
     */
    <T extends Extension> void register(Class<T> extensionPoint, T extension);

    /** 查询当前插件注册的扩展点实现。跨插件聚合查询请使用 core 的 PluginManager。 */
    <T extends Extension> List<T> lookup(Class<T> extensionPoint);

    /** 统一命名规则的日志器。 */
    System.Logger logger();
}
