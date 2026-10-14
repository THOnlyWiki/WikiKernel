package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.service.SiteSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实插件 jar 的端到端验证：用 {@code plugin/dep-demo/build/libs} 里的 dep-demo jar 启动一次应用，
 * 断言平台能力在**真实实例**上生效：
 * <ol>
 *     <li>{@code plugin.requiresCore=>=0.0.1}：core 版本约束被接受，插件正常启动；</li>
 *     <li>{@code plugin.clientRequires=comments}：{@code clientLoadOrder()} 含 dep-demo；
 *         这里只装了 dep-demo，因此 comments 缺失 → {@code clientDependencyWarnings()} 给出 HTML 注释；</li>
 *     <li>{@code CoreAopHook}：core 的 {@code SiteSettingsService#get} 被切面命中（读插件里的计数器）；</li>
 *     <li>插件**自己的 Bean**（{@code DepDemoService#greet}）同样被切面命中。</li>
 * </ol>
 * 需要先构建插件（{@code .\gradlew.bat :plugin:dep-demo:build}）；没有 jar 时用例自动跳过。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class DepDemoPluginTest {

    private static final Path PLUGIN_JAR_DIR = depDemoJarDirectory();

    @DynamicPropertySource
    static void pluginProperties(DynamicPropertyRegistry registry) {
        registry.add("wikikernel.plugins.dir", () -> PLUGIN_JAR_DIR.toString());
        registry.add("wikikernel.plugins.classpath-scan", () -> false);
    }

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Test
    void depDemoStartsAndDeclaresItsDependencies() {
        LoadedPlugin loaded = loadedDepDemo();

        assertEquals(">=0.0.1", loaded.descriptor().requiresCore());
        assertTrue(loaded.descriptor().requires().isEmpty(), "本插件不依赖其它插件的后端能力");
        assertEquals(List.of("comments"), loaded.descriptor().clientRequires());
        assertTrue(loaded.source().endsWith(".jar"), "来源应当是插件 jar: " + loaded.source());
    }

    @Test
    void clientLoadOrderContainsDepDemoAndWarnsAboutMissingComments() {
        assertTrue(pluginManager.clientLoadOrder().contains("dep-demo"));

        List<String> warnings = pluginManager.clientDependencyWarnings();
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("dep-demo") && warning.contains("comments")),
                "comments 未加载时应留 HTML 注释警告: " + warnings);
        assertTrue(warnings.stream().allMatch(warning -> warning.startsWith("<!--") && warning.endsWith("-->")),
                "警告必须是 HTML 注释（页面不能 500）: " + warnings);
    }

    @Test
    void headItemOfDepDemoIsCollected() {
        List<String> items = pluginManager.headProviders(true).stream()
                .flatMap(provider -> provider.headItems(null).stream())
                .toList();

        assertTrue(items.stream().anyMatch(item -> item.contains("dep-demo")), "dep-demo 的 head 片段: " + items);
    }

    @Test
    void pluginAdvisorAdvisesCoreBean() throws Exception {
        AtomicInteger counter = pluginCounter("GET_CALLS");

        assertTrue(AopUtils.isAopProxy(siteSettingsService), "core Bean 应当被插件 AOP 桥代理");
        int before = counter.get();
        String value = siteSettingsService.get("dep-demo.test.key", "fallback");

        assertEquals("fallback", value);
        assertEquals(before + 1, counter.get(), "插件切面应当命中 core 的 SiteSettingsService#get");
    }

    @Test
    void pluginAdvisorAlsoAdvisesPluginOwnBean() throws Exception {
        AtomicInteger counter = pluginCounter("GREET_CALLS");
        ConfigurableApplicationContext pluginContext = loadedDepDemo().springContext();

        Object service = pluginContext.getBeansOfType(Object.class).values().stream()
                .filter(bean -> bean.getClass().getName()
                        .startsWith("cc.thonly.wikikernel.plugin.depdemo.service.DepDemoService"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("插件自己的 DepDemoService Bean 没有找到"));

        assertTrue(AopUtils.isAopProxy(service), "插件自己的 Bean 也应当被桥接代理");
        int before = counter.get();
        Object result = service.getClass().getMethod("greet", String.class).invoke(service, "dep-demo");

        assertEquals("hello dep-demo", result);
        assertEquals(before + 1, counter.get(), "插件切面应当命中插件自己的 Bean");
    }

    /** 已加载的 dep-demo；没有构建插件（jar 不存在）时整个用例自动跳过。 */
    private LoadedPlugin loadedDepDemo() {
        Optional<LoadedPlugin> loaded = pluginManager.find("dep-demo");
        assumeTrue(loaded.isPresent(), "dep-demo 未加载（先跑 :plugin:dep-demo:build 生成 jar）");
        return loaded.orElseThrow();
    }

    /** 通过插件类加载器读取 dep-demo 的静态计数器（插件与 core 之间没有编译期依赖）。 */
    private AtomicInteger pluginCounter(String field) throws Exception {
        ClassLoader classLoader = loadedDepDemo().classLoader();
        Class<?> hook = Class.forName("cc.thonly.wikikernel.plugin.depdemo.hook.DepDemoAopHook", true, classLoader);
        return (AtomicInteger) hook.getField(field).get(null);
    }

    /**
     * dep-demo 的构建产物目录：优先用根构建脚本给出的仓库 {@code plugins/} 目录反推仓库根
     * （{@code wk.plugins.dir} 系统属性），IDE 下退化为相对路径。
     */
    private static Path depDemoJarDirectory() {
        String repoPlugins = System.getProperty("wk.plugins.dir");
        if (repoPlugins != null && !repoPlugins.isBlank()) {
            Path parent = Path.of(repoPlugins).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent.resolve("plugin").resolve("dep-demo").resolve("build").resolve("libs");
            }
        }
        return Path.of("..", "..", "..", "plugin", "dep-demo", "build", "libs").toAbsolutePath().normalize();
    }
}
