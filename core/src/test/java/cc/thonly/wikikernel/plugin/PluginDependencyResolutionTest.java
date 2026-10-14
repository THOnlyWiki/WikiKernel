package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.plugin.testfixture.FixturePlugin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件运行期依赖（{@code plugin.requires} / {@code plugin.requiresCore}）与客户端依赖
 * （{@code plugin.clientRequires}）的端到端测试。
 * <p>
 * 做法：为 {@link FixturePlugin} 现场生成若干**只有描述文件**的插件 jar（入口类在 core 的测试类路径上，
 * 由插件类加载器委派给父加载器找到），把 {@code wikikernel.plugins.dir} 指向这个临时目录，
 * 于是 {@link PluginManager} 走的是和真实部署完全一样的发现 / 排序 / 启动路径：
 * <ul>
 *     <li>{@code p-base → p-app → p-app2}：启动顺序与 head 注入顺序都必须是「被依赖者在前」；</li>
 *     <li>{@code p-needs-missing} / {@code p-needs-skipped} / {@code p-needs-disabled}：依赖缺失时跳过启动；</li>
 *     <li>{@code p-cycle-a ↔ p-cycle-b}：循环依赖 → 环上插件全部跳过（应用照常启动）；</li>
 *     <li>{@code p-core-too-new}（{@code requiresCore=>=99.0.0}）：core 版本不满足 → 跳过启动；</li>
 *     <li>{@code p-core-ok}（{@code requiresCore=>=0.0.1}）：{@code 0.0.1-SNAPSHOT} 满足 → 正常启动；</li>
 *     <li>{@code p-client-missing}（{@code clientRequires=p-ghost}）：**照常启动**，只留 HTML 注释 + WARN。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PluginDependencyResolutionTest {

    /** 现场生成的插件 jar：id + requires + requiresCore + clientRequires + 是否进入 enabled 白名单。 */
    private record PluginSpec(String id, String requires, String requiresCore, String clientRequires,
                              boolean enabled) {
    }

    private static final List<PluginSpec> SPECS = List.of(
            new PluginSpec("p-base", "", "", "", true),
            new PluginSpec("p-app", "p-base", "", "p-base", true),
            new PluginSpec("p-app2", "p-app", "", "p-app", true),
            new PluginSpec("p-core-ok", "", ">=0.0.1", "", true),
            new PluginSpec("p-client-missing", "", "", "p-ghost", true),
            new PluginSpec("p-needs-missing", "p-ghost", "", "", true),
            new PluginSpec("p-needs-skipped", "p-needs-missing", "", "", true),
            new PluginSpec("p-cycle-a", "p-cycle-b", "", "", true),
            new PluginSpec("p-cycle-b", "p-cycle-a", "", "", true),
            new PluginSpec("p-core-too-new", "", ">=99.0.0", "", true),
            new PluginSpec("p-disabled", "", "", "", false),
            new PluginSpec("p-needs-disabled", "p-disabled", "", "", true));

    private static final Path PLUGIN_DIR = createPluginJars();

    @DynamicPropertySource
    static void pluginProperties(DynamicPropertyRegistry registry) {
        registry.add("wikikernel.plugins.dir", () -> PLUGIN_DIR.toString());
        registry.add("wikikernel.plugins.classpath-scan", () -> false);
        registry.add("wikikernel.plugins.aop-bridge", () -> false);
        registry.add("wikikernel.plugins.enabled", () -> SPECS.stream()
                .filter(PluginSpec::enabled)
                .map(PluginSpec::id)
                .reduce((left, right) -> left + "," + right)
                .orElse(""));
    }

    @Autowired
    private PluginManager pluginManager;

    @Test
    void onlyPluginsWithSatisfiedDependenciesStart() {
        assertEquals(List.of("p-app", "p-app2", "p-base", "p-client-missing", "p-core-ok"), loadedIds());
    }

    @Test
    void requiredPluginsStartFirst() {
        List<String> order = FixturePlugin.START_ORDER;

        assertTrue(order.containsAll(List.of("p-base", "p-app", "p-app2")), "启动顺序: " + order);
        assertTrue(order.indexOf("p-base") < order.indexOf("p-app"), "被依赖的 p-base 必须先启动: " + order);
        assertTrue(order.indexOf("p-app") < order.indexOf("p-app2"), "依赖要传递: " + order);
    }

    @Test
    void missingDependencySkipsPluginAndItsDependents() {
        assertTrue(pluginManager.find("p-needs-missing").isEmpty(), "依赖未安装 → 跳过启动");
        assertTrue(pluginManager.find("p-needs-skipped").isEmpty(), "依赖被跳过 → 本插件也跳过");
        assertTrue(pluginManager.find("p-needs-disabled").isEmpty(), "依赖被白名单禁用 → 本插件也跳过");
    }

    @Test
    void cyclicDependencySkipsWholeCycleWithoutFailingStartup() {
        assertTrue(pluginManager.find("p-cycle-a").isEmpty());
        assertTrue(pluginManager.find("p-cycle-b").isEmpty());
        // 环以外的插件不受影响
        assertTrue(pluginManager.find("p-base").isPresent());
    }

    @Test
    void coreVersionConstraintIsEnforced() {
        assertTrue(pluginManager.find("p-core-ok").isPresent(), ">=0.0.1 应当被 0.0.1-SNAPSHOT 满足");
        assertTrue(pluginManager.find("p-core-too-new").isEmpty(), ">=99.0.0 不满足 → 跳过启动");
    }

    @Test
    void clientLoadOrderFollowsClientRequires() {
        List<String> order = pluginManager.clientLoadOrder();

        assertTrue(order.containsAll(List.of("p-base", "p-app", "p-app2", "p-client-missing")),
                "客户端顺序: " + order);
        assertTrue(order.indexOf("p-base") < order.indexOf("p-app"), "客户端顺序: " + order);
        assertTrue(order.indexOf("p-app") < order.indexOf("p-app2"), "客户端顺序: " + order);
    }

    @Test
    void headProvidersFollowClientLoadOrder() {
        List<String> markers = pluginManager.headProviders(false).stream()
                .map(provider -> provider.headItems(null))
                .flatMap(List::stream)
                .toList();

        assertTrue(markers.contains(FixturePlugin.FixtureHeadProvider.marker("p-base")), "head 片段: " + markers);
        assertTrue(markers.indexOf(FixturePlugin.FixtureHeadProvider.marker("p-base"))
                        < markers.indexOf(FixturePlugin.FixtureHeadProvider.marker("p-app")),
                "被依赖插件的 head 片段必须排在前面: " + markers);
        assertTrue(markers.indexOf(FixturePlugin.FixtureHeadProvider.marker("p-app"))
                        < markers.indexOf(FixturePlugin.FixtureHeadProvider.marker("p-app2")),
                "被依赖插件的 head 片段必须排在前面: " + markers);
    }

    @Test
    void missingClientDependencyOnlyProducesHtmlCommentWarning() {
        List<String> warnings = pluginManager.clientDependencyWarnings();

        assertEquals(1, warnings.size(), "只有 p-client-missing 声明了缺失的客户端依赖: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.startsWith("<!--") && warning.endsWith("-->"), warning);
        assertTrue(warning.contains("p-client-missing") && warning.contains("p-ghost"), warning);
        assertTrue(pluginManager.find("p-client-missing").isPresent(), "客户端依赖缺失不影响插件启动");
        assertFalse(pluginManager.clientLoadOrder().contains("p-ghost"));
    }

    // ==================== 测试数据准备 ====================

    private List<String> loadedIds() {
        return pluginManager.loadedPlugins().stream()
                .map(loaded -> loaded.descriptor().id())
                .sorted()
                .toList();
    }

    /** 生成插件 jar：只有 {@code META-INF/wiki-plugin.properties}，入口类指向测试类路径上的 {@link FixturePlugin}。 */
    private static Path createPluginJars() {
        try {
            Path directory = Files.createTempDirectory("wikikernel-plugin-deps");
            for (PluginSpec spec : SPECS) {
                writePluginJar(directory, spec);
            }
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("生成测试插件 jar 失败", exception);
        }
    }

    private static void writePluginJar(Path directory, PluginSpec spec) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("plugin.id", spec.id());
        properties.put("plugin.name", "Fixture " + spec.id());
        properties.put("plugin.version", "1.0.0");
        properties.put("plugin.description", "core 测试替身插件（现场生成）");
        properties.put("plugin.mainClass", FixturePlugin.class.getName());
        properties.put("plugin.requires", spec.requires());
        properties.put("plugin.requiresCore", spec.requiresCore());
        properties.put("plugin.clientRequires", spec.clientRequires());

        StringBuilder content = new StringBuilder();
        properties.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));

        Path jar = directory.resolve(spec.id() + ".jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            jarOutput.putNextEntry(new JarEntry("META-INF/wiki-plugin.properties"));
            jarOutput.write(content.toString().getBytes(StandardCharsets.UTF_8));
            jarOutput.closeEntry();
        }
    }
}
