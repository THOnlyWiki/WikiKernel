package cc.thonly.wikikernel.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/**
 * 插件类加载器：每个插件 jar 一个，父加载器为 core 的类加载器。
 * <p>
 * 使用默认的“父优先”策略，因此 {@code cc.thonly.wikikernel.common} 下的 SPI 类型
 * 由 core 统一提供，插件与 core 拿到的是同一个 Class 对象，扩展点注册/查询才能正确匹配。
 * <p>
 * 注意：插件 jar 中不要打包 common（Gradle 的 {@code implementation project(':common')} 不会打进去）。
 */
public class PluginClassLoader extends URLClassLoader {

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    public String toString() {
        return "PluginClassLoader" + Arrays.toString(getURLs());
    }
}
