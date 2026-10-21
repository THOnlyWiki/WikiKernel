package cc.thonly.wikikernel.plugin;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * core 对 Mixin 启动器的只读视图。
 *
 * <p>Mixin 的初始化发生在 Spring 启动之前（由 {@code mixin-agent} 完成），
 * core 不直接依赖 Mixin 或 agent 类，只通过系统属性了解：</p>
 *
 * <ul>
 *     <li>{@code wikikernel.mixin.active=true}：本次启动成功启用了 Mixin；</li>
 *     <li>{@code wikikernel.mixin.plugins=id1,id2}：启动时发现并装载了 Mixin 配置的插件 id。</li>
 * </ul>
 *
 * <p>带 Mixin 的插件类会进入应用类加载器，因此不能像普通插件那样热替换；
 * 普通插件仍然使用独立类加载器并支持运行时装卸。</p>
 */
public final class MixinSupport {

    private static final String ACTIVE_PROPERTY = "wikikernel.mixin.active";
    private static final String PLUGINS_PROPERTY = "wikikernel.mixin.plugins";

    private MixinSupport() {
    }

    public static boolean isActive() {
        return Boolean.parseBoolean(System.getProperty(ACTIVE_PROPERTY, "false"));
    }

    public static boolean isStartupPlugin(String pluginId) {
        return pluginIds().contains(pluginId);
    }

    /**
     * 是否是启动期由 agent 装载的 Mixin 插件。
     * <p>只有 agent 成功启用时才返回 true；agent 未加载时普通插件仍可启动，
     * 但 Mixin 不会生效，{@link PluginManager} 会给出警告。</p>
     */
    public static boolean isMixinPlugin(String pluginId) {
        return isActive() && isStartupPlugin(pluginId);
    }

    public static Set<String> pluginIds() {
        String value = System.getProperty(PLUGINS_PROPERTY, "").trim();
        if (value.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
