package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.Closeable;

/**
 * 已加载插件。
 *
 * @param descriptor    插件元信息
 * @param plugin        插件实例（由插件的 Spring 子容器管理）
 * @param context       插件运行上下文
 * @param source        来源（jar 文件名或 classpath）
 * @param classLoader   插件独立类加载器，classpath 来源时为 null
 * @param springContext 插件的 Spring 子容器（父容器是 core 主容器）
 */
public record LoadedPlugin(PluginDescriptor descriptor,
                           WikiPlugin plugin,
                           SimplePluginContext context,
                           String source,
                           PluginClassLoader classLoader,
                           ConfigurableApplicationContext springContext) {
}
