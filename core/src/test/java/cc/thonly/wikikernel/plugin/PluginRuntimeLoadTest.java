package cc.thonly.wikikernel.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 插件**运行时装载**回归测试：用根目录 {@code plugins/} 里的真实插件 jar 启动一次应用，
 * 断言每一个 jar 都成功启动（{@code PluginManager.loadedPlugins()} 数量与 jar 数量一致）；
 * 并验证**运行时副本与动态装卸**：加载的是 {@code run/.plugins} 下的内容哈希副本，
 * 源 jar 不被锁定（运行中可改名 / 删除），{@code POST /api/plugins/reload} 等效的
 * {@link PluginManager#reload()} 能自动卸载 / 重新启动插件。
 * <p>
 * 为什么需要它：插件的 {@code @RestController} 由插件子容器的组件扫描创建，
 * 如果它依赖的对象只是在 {@code onStart} 里 {@code new} 出来、没有成为 Bean，
 * 单元测试（不装载插件）是发现不了的——本测试专门拦住这类「插件在真实实例上启动失败」。
 * <p>
 * 需要先构建插件并复制到根目录：{@code .\gradlew.bat installPlugins}。
 * 没构建过（目录不存在 / 没有 jar）时**自动跳过**，不会影响普通的 {@code gradlew build}。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        // 插件目录由 build.gradle 的 wk.plugins.dir 系统属性给出（仓库根 plugins/）
        "wikikernel.plugins.dir=${wk.plugins.dir:../plugins}"
})
class PluginRuntimeLoadTest {

    @Autowired
    private PluginManager pluginManager;

    @Test
    void everyPackagedPluginStarts() throws IOException {
        String configured = System.getProperty("wk.plugins.dir", "../plugins");
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(directory), "未找到 " + directory + "（先跑 installPlugins）");
        List<Path> jars;
        try (Stream<Path> files = Files.list(directory)) {
            jars = files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
        }
        assumeTrue(!jars.isEmpty(), "plugins/ 里没有 jar（先跑 installPlugins）");

        List<String> loaded = pluginManager.loadedPlugins().stream()
                .map(plugin -> plugin.descriptor().id())
                .sorted()
                .toList();
        assertEquals(jars.size(), loaded.size(),
                "有插件启动失败：jar " + jars.size() + " 个，成功启动 " + loaded.size() + " 个 → " + loaded);
        assertTrue(loaded.contains("comments") && loaded.contains("gadgets") && loaded.contains("social-profile")
                        && loaded.contains("moderation") && loaded.contains("abuse-filter"),
                "第一批治理类插件都应成功启动：" + loaded);
    }

    /**
     * 动态装卸：源 jar 改名（删除）→ reload 自动卸载；恢复 → reload 自动重新启动。
     * <p>
     * 顺带验证「不锁定源文件」：Windows 上如果 JVM 正打开源 jar，{@code Files.move} 会直接
     * 抛 {@code AccessDeniedException}（「文件被另一个程序占用」）——这一步失败即说明没走副本加载。
     */
    @Test
    void reloadUnloadsRemovedPluginAndRestoresIt() throws IOException {
        String configured = System.getProperty("wk.plugins.dir", "../plugins");
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(directory), "未找到 " + directory + "（先跑 installPlugins）");

        // 加载的是运行时副本：目录里应有 <名字>-<12 位哈希>.jar
        Path runtimeDir = pluginManager.runtimeDirectory();
        assertTrue(Files.isDirectory(runtimeDir), "运行时副本目录应存在: " + runtimeDir);
        try (Stream<Path> files = Files.list(runtimeDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                            .matches(".+-[0-9a-f]{12}\\.jar")),
                    "运行时副本应按内容哈希命名: " + runtimeDir);
        }

        LoadedPlugin target = pluginManager.loadedPlugins().stream()
                .filter(loaded -> loaded.source() != null && !"classpath".equals(loaded.source()))
                .max(Comparator.comparing(loaded -> loaded.descriptor().id()))
                .orElseThrow(() -> new IllegalStateException("没有「目录来源」的插件可测试"));
        String pluginId = target.descriptor().id();
        Path sourceJar = directory.resolve(target.source());
        assumeTrue(Files.isRegularFile(sourceJar), "源 jar 不存在: " + sourceJar);
        Path backup = directory.resolve(target.source() + ".reload-test.bak");

        try {
            Files.move(sourceJar, backup);
            PluginManager.ReloadResult removed = pluginManager.reload();
            assertTrue(removed.unloaded().contains(pluginId),
                    "删除源 jar 后 reload 应卸载 " + pluginId + " → " + removed);
            assertTrue(pluginManager.find(pluginId).isEmpty(), pluginId + " 应已卸载");

            Files.move(backup, sourceJar);
            PluginManager.ReloadResult restored = pluginManager.reload();
            assertTrue(restored.started().contains(pluginId),
                    "恢复源 jar 后 reload 应重新启动 " + pluginId + " → " + restored);
            assertTrue(pluginManager.find(pluginId).isPresent(), pluginId + " 应已恢复");
        } finally {
            if (Files.exists(backup)) {
                Files.move(backup, sourceJar, StandardCopyOption.REPLACE_EXISTING);
                pluginManager.reload();
            }
            Assumptions.assumeTrue(pluginManager.find(pluginId).isPresent(), pluginId + " 未恢复到加载状态");
        }
    }
}
