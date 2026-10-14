package cc.thonly.wikikernel.common.plugin;

import java.util.List;

/**
 * 插件 SPI（Service Provider Interface），插件唯一需要实现的入口接口。
 * <p>
 * 约定：插件只允许依赖 wiki-common，禁止依赖 core 或其他插件；
 * core 与插件之间因此不存在编译期依赖，从根源上避免依赖循环。
 * <p>
 * 实现类必须提供公开的无参构造方法。
 */
public interface WikiPlugin {

    /** 插件元信息，id 必须全局唯一。 */
    PluginDescriptor descriptor();

    /**
     * 插件被加载时调用一次。
     * 可在此处通过 {@code context.register(...)} 注册扩展点实现。
     */
    default void onStart(PluginContext context) {
    }

    /** 应用关闭或插件被卸载时调用，用于释放资源。 */
    default void onStop(PluginContext context) {
    }

    /**
     * 插件**自己拥有**的数据表（{@code @TableName} 实体类）。
     * <p>
     * core 会在 {@code onStart} **之前**按与 core 相同的规则（{@code @TableName} / {@code @TableId} /
     * {@code @TableField} / {@code @DbColumn}，支持 SQLite / MySQL / H2，只增不减）建表 / 补列，
     * 因此插件可以把专属实体留在自己的 jar 里，用
     * {@link cc.thonly.wikikernel.common.service.PluginDatabase} 读写，不必再往 {@code common.data}
     * 或 {@code common.service} 里放插件专属的实体 / 桥接口。
     * <pre>{@code
     *   @Override
     *   public List<Class<?>> schemaEntities() {
     *       return List.of(WikiExample.class);
     *   }
     * }</pre>
     * 结构初始化失败会让该插件启动失败（只影响这个插件，应用继续启动）；重复装卸（动态 reload）时幂等。
     */
    default List<Class<?>> schemaEntities() {
        return List.of();
    }
}
