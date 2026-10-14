package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.WikiKernelInfo;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.common.plugin.PluginConstants;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.PluginVersionConstraint;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.common.plugin.support.PluginDependencyGraph;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import cc.thonly.wikikernel.config.PluginProperties;
import cc.thonly.wikikernel.db.ddl.PluginSchemaDdl;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 插件加载器，在 core 的 main 方法启动、应用就绪后触发。
 * <p>
 * 加载流程：
 * <ol>
 *     <li>扫描 {@code wikikernel.plugins.dir} 下的插件 jar，**按内容哈希复制到
 *         {@code wikikernel.plugins.runtime-dir}（默认 {@code run/.plugins}）后加载副本**
 *         ——源 jar 不被 JVM 锁定，服务器所有者可随时替换 / 删除（Windows 上不会提示占用）；</li>
 *     <li>每个 jar 使用独立的 PluginClassLoader；</li>
 *     <li>如果开启 classpath 扫描，再用 ServiceLoader 发现 classpath 上的插件（方便 IDE 调试与测试）；</li>
 *     <li>按 {@code wikikernel.plugins.enabled} 白名单过滤、去掉重复 id；</li>
 *     <li><b>按 {@code plugin.requires} 做拓扑排序</b>（被依赖的先启动），并检查 {@code plugin.requiresCore}
 *         与循环依赖：不满足的插件只记日志并跳过，绝不拖垮应用启动；</li>
 *     <li>为每个插件创建 Spring 子容器（父容器是 core），插件里的 Spring 注解全部生效；</li>
 *     <li>把插件 Controller 注册到 core 的 MVC，然后调用 {@link WikiPlugin#onStart}。</li>
 * </ol>
 * 运行时装卸：{@link #reload()}（{@code POST /api/plugins/reload}，仅管理员）会卸载源 jar 已被
 * 删除 / 替换的插件、启动新出现的插件，无需重启应用。
 * <p>
 * 另外提供 {@link #clientLoadOrder()}（{@code plugin.clientRequires} 的注入顺序）与
 * {@link #headProviders(boolean)}（按客户端依赖顺序排好的 head 扩展点）给页面渲染用。
 * <p>
 * core 在编译期不依赖任何插件，插件装配完全发生在运行时，因此 core 与 plugin 之间不存在依赖循环。
 */
@Component
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginProperties properties;
    private final PluginSpringContextFactory springContextFactory;
    private final PluginWebRegistrar webRegistrar;
    /** 插件自有表的 Auto-DDL（{@code WikiPlugin.schemaEntities()}）；测试里可以是 null。 */
    private final PluginSchemaDdl schemaDdl;
    private final List<LoadedPlugin> plugins = new CopyOnWriteArrayList<>();

    /**
     * 源 jar 文件名 → 已加载副本的内容哈希（SHA-256 前 12 位十六进制）。
     * 动态装卸（{@link #reload()}）据此判断源目录里的文件是否被替换；classpath 插件不在其中。
     */
    private final Map<String, String> stagedHashes = new LinkedHashMap<>();

    /** 插件集合的版本号：每次成功启动 / 停止插件自增，供 AOP 桥与客户端顺序做增量刷新。 */
    private volatile long generation;

    private volatile long clientViewGeneration = -1;
    private volatile List<String> clientOrder = List.of();
    private volatile List<String> clientWarnings = List.of();

    public PluginManager(PluginProperties properties,
                         PluginSpringContextFactory springContextFactory,
                         PluginWebRegistrar webRegistrar,
                         PluginSchemaDdl schemaDdl) {
        this.properties = properties;
        this.springContextFactory = springContextFactory;
        this.webRegistrar = webRegistrar;
        this.schemaDdl = schemaDdl;
    }

    /**
     * 等应用完全就绪后再启动插件，保证 MVC 等基础设施已经可用。
     * <p>
     * 顺序标记为最高优先级：AOP 桥（{@code PluginAopBridge}）等订阅同一事件的组件在它之后执行，
     * 这样它们看到的一定是「插件已经全部启动完」的状态。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void start() {
        Map<String, String> unavailable = startDiscovered();
        log.info("插件加载完成，共 {} 个: {}", plugins.size(),
                plugins.stream().map(loaded -> loaded.descriptor().id()).toList());
        if (!unavailable.isEmpty()) {
            log.info("未启动的插件 {} 个: {}", unavailable.size(), unavailable);
        }
        refreshClientView();
    }

    /**
     * 发现并启动**尚未加载**的插件：依赖排序、白名单、重复 id、循环依赖、core 版本约束的容错都在这里；
     * 应用启动（{@link #start()}）与动态装卸（{@link #reload()}）共用。
     *
     * @return 未启动插件 id → 原因（仅用于日志 / API 返回）
     */
    private synchronized Map<String, String> startDiscovered() {
        Map<String, DiscoveredPlugin> candidates = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        for (DiscoveredPlugin discovered : discover()) {
            String id = discovered.descriptor().id();
            if (!isEnabled(id)) {
                log.info("跳过未启用的插件: {}", id);
                unavailable.put(id, "未启用（不在 wikikernel.plugins.enabled 白名单里）");
                closeQuietly(discovered.classLoader());
                continue;
            }
            if (candidates.containsKey(id) || find(id).isPresent()) {
                log.warn("插件 id 重复，忽略后发现的: {}（来源: {}）", id, discovered.source());
                closeQuietly(discovered.classLoader());
                continue;
            }
            candidates.put(id, discovered);
        }

        Map<String, List<String>> requires = new LinkedHashMap<>();
        candidates.forEach((id, discovered) -> requires.put(id, discovered.descriptor().requires()));
        PluginDependencyGraph.Result graph = PluginDependencyGraph.sort(requires);

        // 循环依赖：环上所有插件跳过 + ERROR 日志（不要让应用启动失败）
        for (String id : graph.cyclic()) {
            List<String> path = graph.cyclePaths().get(id);
            log.error("插件 {} 存在循环依赖（{}），已跳过启动；请修改 plugin.requires 打破环（应用继续启动）",
                    id, path == null ? id + " → " + id : String.join(" → ", path));
            unavailable.put(id, "循环依赖");
        }
        for (String id : graph.blocked()) {
            log.warn("插件 {} 依赖了处于循环依赖中的插件，已跳过启动（应用继续启动）", id);
            unavailable.put(id, "依赖了处于循环依赖中的插件");
        }

        Set<String> started = new LinkedHashSet<>();
        for (String id : graph.order()) {
            DiscoveredPlugin discovered = candidates.get(id);
            PluginDescriptor descriptor = discovered.descriptor();

            List<String> missing = graph.missing().getOrDefault(id, List.of());
            if (!missing.isEmpty()) {
                log.warn("插件 {} 缺少运行期依赖 {}（未安装或被白名单禁用，见 plugin.requires），已跳过启动",
                        id, missing);
                unavailable.put(id, "缺少运行期依赖 " + missing);
                closeQuietly(discovered.classLoader());
                continue;
            }

            List<String> notStarted = descriptor.requires().stream()
                    .filter(dependency -> !started.contains(dependency))
                    .toList();
            if (!notStarted.isEmpty()) {
                String detail = notStarted.stream()
                        .map(dependency -> dependency + "（" + unavailable.getOrDefault(dependency, "未启动") + "）")
                        .collect(Collectors.joining("、"));
                log.warn("插件 {} 依赖的插件没有启动: {}，已跳过启动（应用继续启动）", id, detail);
                unavailable.put(id, "依赖的插件未启动 " + notStarted);
                closeQuietly(discovered.classLoader());
                continue;
            }

            PluginVersionConstraint constraint = descriptor.coreConstraint();
            if (!constraint.matches(WikiKernelInfo.VERSION)) {
                log.warn("插件 {} 要求 core {}，当前 core 版本是 {}（plugin.requiresCore），已跳过启动（应用继续启动）",
                        id, constraint, WikiKernelInfo.VERSION);
                unavailable.put(id, "core 版本不满足（要求 " + constraint + "，当前 " + WikiKernelInfo.VERSION + "）");
                closeQuietly(discovered.classLoader());
                continue;
            }

            if (load(discovered)) {
                started.add(id);
            } else {
                unavailable.put(id, "启动失败（见 ERROR 日志）");
            }
        }

        return unavailable;
    }

    @PreDestroy
    public void stop() {
        for (LoadedPlugin loaded : List.copyOf(plugins)) {
            unload(loaded);
        }
        plugins.clear();
        this.generation++;
        refreshClientView();
    }

    /**
     * 运行时重新扫描插件目录（服务器所有者动态装卸插件，{@code POST /api/plugins/reload}）：
     * <ol>
     *     <li>卸载**源 jar 已被删除 / 已被替换**的插件（IDE 调试用的 classpath 插件不参与）；</li>
     *     <li>启动插件目录里新出现的插件（依赖排序等逻辑与启动时完全一致）；</li>
     *     <li>刷新客户端注入顺序。</li>
     * </ol>
     * 加载的一直是 {@code wikikernel.plugins.runtime-dir} 下的内容哈希副本，替换 / 删除
     * {@code plugins/} 里的 jar 不会被 JVM 占用（Windows 上不会提示「文件被另一个程序占用」）。
     * 单个插件失败只记日志，不影响其它插件与应用。
     */
    public synchronized ReloadResult reload() {
        List<String> unloaded = new ArrayList<>();
        for (LoadedPlugin loaded : List.copyOf(plugins)) {
            String source = loaded.source();
            if (source == null || "classpath".equals(source)) {
                continue;
            }
            String reason = removalReason(sourceDirectory().resolve(source), source);
            if (reason != null) {
                String hash = stagedHashes.get(source);
                unload(loaded);
                unloaded.add(loaded.descriptor().id());
                if (hash != null) {
                    deleteStagedCopy(source, hash);
                }
                log.info("插件 {} 已卸载（{}）", loaded.descriptor().id(), reason);
            }
        }
        Set<String> before = plugins.stream()
                .map(loaded -> loaded.descriptor().id())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> unavailable = startDiscovered();
        List<String> started = plugins.stream()
                .map(loaded -> loaded.descriptor().id())
                .filter(id -> !before.contains(id))
                .toList();
        refreshClientView();
        if (!started.isEmpty() || !unloaded.isEmpty()) {
            log.info("插件动态装卸完成：启动 {}，卸载 {}", started, unloaded);
        }
        return new ReloadResult(started, unloaded, unavailable);
    }

    /** 源 jar 需要卸载的原因：{@code null} = 未变化（文件没了 / 内容哈希变了才卸载）。 */
    private String removalReason(Path sourceJar, String sourceName) {
        if (!Files.isRegularFile(sourceJar)) {
            return "源文件已删除";
        }
        try {
            String current = hashOf(Files.readAllBytes(sourceJar));
            String previous = stagedHashes.get(sourceName);
            return previous != null && !previous.equals(current) ? "源文件已替换" : null;
        } catch (IOException exception) {
            log.warn("读取插件源文件失败（保持已加载版本）: {}", sourceJar, exception);
            return null;
        }
    }

    /**
     * 卸载一个已加载插件：{@code onStop} → 注销 MVC → 关闭 Spring 子容器与 PluginClassLoader
     * （副本文件随之解锁，可在下次发现时清理）。
     */
    private void unload(LoadedPlugin loaded) {
        try {
            loaded.plugin().onStop(loaded.context());
        } catch (RuntimeException exception) {
            log.warn("插件停止失败: {}", loaded.descriptor().id(), exception);
        }
        webRegistrar.unregister(loaded.descriptor().id());
        closeQuietly(loaded.springContext());
        closeQuietly(loaded.classLoader());
        plugins.remove(loaded);
        if (loaded.source() != null) {
            stagedHashes.remove(loaded.source());
        }
        this.generation++;
    }

    /** 动态装卸的结果摘要（{@code POST /api/plugins/reload} 的返回体；{@code unavailable} = 没起来的插件 → 原因）。 */
    public record ReloadResult(List<String> started, List<String> unloaded, Map<String, String> unavailable) {
    }

    /** 按 id 查找已经加载的插件。 */
    public Optional<LoadedPlugin> find(String pluginId) {
        return plugins.stream()
                .filter(loaded -> loaded.descriptor().id().equals(pluginId))
                .findFirst();
    }

    /** 当前已加载插件列表。 */
    public List<LoadedPlugin> loadedPlugins() {
        return List.copyOf(plugins);
    }

    /** 插件集合版本号：成功启动 / 停止插件都会自增（AOP 桥与客户端顺序据此做增量刷新）。 */
    public long generation() {
        return this.generation;
    }

    /** 已加载插件的类加载器（供插件模板解析等使用）。 */
    public List<ClassLoader> pluginClassLoaders() {
        return plugins.stream()
                .map(LoadedPlugin::classLoader)
                .filter(PluginClassLoader.class::isInstance)
                .map(PluginClassLoader.class::cast)
                .map(classLoader -> (ClassLoader) classLoader)
                .toList();
    }

    /** 聚合所有插件注册的扩展点实现，这是跨插件协作的唯一通道。 */
    public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
        List<T> result = new ArrayList<>();
        for (LoadedPlugin loaded : plugins) {
            result.addAll(loaded.context().lookup(extensionPoint));
        }
        return List.copyOf(result);
    }

    // ==================== 客户端（前端资源）依赖 ====================

    /**
     * 客户端注入顺序：按 {@code plugin.clientRequires} 拓扑排序后的**已加载插件 id**（被依赖者在前）。
     * <p>
     * 循环依赖只打乱顺序并记 WARN，不会让插件从页面上消失（见
     * {@link PluginDependencyGraph.Result#orderWithBrokenCycles()}）。
     */
    public List<String> clientLoadOrder() {
        refreshClientView();
        return this.clientOrder;
    }

    /**
     * 客户端依赖缺失时的 HTML 注释警告（插件在跑、但它声明的前端依赖插件没加载）：
     * 直接塞进 {@code <head>} 的片段里，页面**不会 500**；同时有一条 WARN 日志。
     */
    public List<String> clientDependencyWarnings() {
        refreshClientView();
        return this.clientWarnings;
    }

    /**
     * 按客户端依赖顺序返回 head 扩展点实现：被依赖插件的片段排在依赖者之前；
     * 同一个插件内部的多个实现仍按 {@link HtmlHeadProvider#order()} 升序。
     *
     * @param siteWideOnly true = 只要 {@link HtmlHeadProvider#siteWide()} 的实现（特殊页 / 编辑页等全站注入）
     */
    public List<HtmlHeadProvider> headProviders(boolean siteWideOnly) {
        List<HtmlHeadProvider> remaining = extensions(HtmlHeadProvider.class).stream()
                .filter(provider -> !siteWideOnly || provider.siteWide())
                .sorted(Comparator.comparingInt(HtmlHeadProvider::order))
                .collect(Collectors.toCollection(ArrayList::new));
        if (plugins.isEmpty()) {
            // 没有已加载插件（含测试里的替身）：保持原有顺序，行为与迁移前一致
            return List.copyOf(remaining);
        }
        List<HtmlHeadProvider> ordered = new ArrayList<>(remaining.size());
        for (String id : clientLoadOrder()) {
            find(id).ifPresent(loaded -> {
                for (HtmlHeadProvider provider : loaded.context().lookup(HtmlHeadProvider.class)) {
                    if (siteWideOnly && !provider.siteWide()) {
                        continue;
                    }
                    if (remaining.remove(provider)) {
                        ordered.add(provider);
                    }
                }
            });
        }
        ordered.addAll(remaining);
        return List.copyOf(ordered);
    }

    /** 重新计算客户端注入顺序与缺失依赖告警（插件集合变化时调用；每代只算一次）。 */
    private void refreshClientView() {
        long current = this.generation;
        if (current == this.clientViewGeneration) {
            return;
        }
        synchronized (this) {
            if (current == this.clientViewGeneration) {
                return;
            }
            Map<String, List<String>> clientRequires = new LinkedHashMap<>();
            for (LoadedPlugin loaded : plugins) {
                clientRequires.put(loaded.descriptor().id(), loaded.descriptor().clientRequires());
            }
            PluginDependencyGraph.Result graph = PluginDependencyGraph.sort(clientRequires);
            if (!graph.cyclic().isEmpty()) {
                log.warn("客户端依赖（plugin.clientRequires）存在循环依赖: {} 与 {}，只影响 head 注入顺序（应用继续运行）",
                        graph.cyclic(), graph.cyclePaths());
            }
            List<String> order = graph.orderWithBrokenCycles();
            List<String> warnings = new ArrayList<>();
            for (String id : order) {
                for (String dependency : graph.missing().getOrDefault(id, List.of())) {
                    warnings.add("<!-- WikiKernel: 插件 " + id + " 的客户端依赖插件 " + dependency
                            + " 未加载（plugin.clientRequires）；相关前端脚本 / 样式可能不可用 -->");
                }
            }
            if (!warnings.isEmpty()) {
                log.warn("客户端依赖缺失（plugin.clientRequires）: {}", graph.missing());
            }
            this.clientOrder = order;
            this.clientWarnings = List.copyOf(warnings);
            this.clientViewGeneration = current;
        }
    }

    // ==================== 内部实现 ====================

    private List<DiscoveredPlugin> discover() {
        List<DiscoveredPlugin> discovered = new ArrayList<>(discoverFromDirectory());
        if (properties.isClasspathScan()) {
            discovered.addAll(discoverFromClasspath());
        }
        return discovered;
    }

    private List<DiscoveredPlugin> discoverFromDirectory() {
        Path directory = sourceDirectory();
        if (!Files.isDirectory(directory)) {
            log.info("插件目录不存在，跳过扫描: {}", directory);
            return List.of();
        }

        List<Path> jars;
        try (Stream<Path> files = Files.list(directory)) {
            jars = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            log.warn("扫描插件目录失败: {}", directory, exception);
            return List.of();
        }

        List<DiscoveredPlugin> result = new ArrayList<>();
        for (Path jar : jars) {
            String sourceName = jar.getFileName().toString();
            Path stagedJar;
            String hash;
            try {
                byte[] bytes = Files.readAllBytes(jar);
                hash = hashOf(bytes);
                stagedJar = store(sourceName, hash, bytes);
            } catch (IOException exception) {
                log.warn("准备插件运行时副本失败（已跳过）: {}", jar, exception);
                continue;
            }
            PluginClassLoader classLoader;
            try {
                classLoader = new PluginClassLoader(new URL[]{stagedJar.toUri().toURL()}, getClass().getClassLoader());
            } catch (IOException exception) {
                log.warn("创建插件类加载器失败（已跳过）: {}", jar, exception);
                continue;
            }
            List<DiscoveredPlugin> found = List.of();
            try {
                found = readFromJar(stagedJar, sourceName, classLoader);
            } catch (IOException | RuntimeException | ServiceConfigurationError exception) {
                // 坏 jar / 描述文件指向不存在的入口类等：跳过该插件，不影响应用启动与其它插件
                log.warn("读取插件 jar 失败（已跳过）: {}", jar, exception);
            }
            if (found.isEmpty()) {
                closeQuietly(classLoader);
            } else {
                stagedHashes.put(sourceName, hash);
                result.addAll(found);
            }
        }
        return result;
    }

    /** 插件源目录（{@code wikikernel.plugins.dir}，相对启动时的工作目录）。 */
    private Path sourceDirectory() {
        return Path.of(properties.getDir()).toAbsolutePath().normalize();
    }

    /**
     * 把源 jar 复制成运行时副本 {@code <runtime-dir>/<名字>-<内容哈希前 12 位>.jar} 并返回副本路径。
     * 加载的是副本，源 jar 不被 JVM 锁定（替换 / 删除不会提示「文件被占用」）；同一内容复用已有副本，
     * 不同内容的旧副本尽力清理（被其它实例 / 本进程的旧类加载器占用时静默跳过）。
     */
    private Path store(String sourceName, String hash, byte[] bytes) throws IOException {
        Path runtimeDir = Path.of(properties.getRuntimeDir()).toAbsolutePath().normalize();
        Files.createDirectories(runtimeDir);
        Path target = runtimeDir.resolve(stagedName(sourceName, hash));
        if (!Files.exists(target)) {
            Path temp = runtimeDir.resolve(stagedName(sourceName, hash) + ".tmp");
            Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        cleanupStaleCopies(runtimeDir, baseName(sourceName), target);
        return target;
    }

    /** 运行时副本的文件名：{@code <源文件名去 .jar>-<12 位内容哈希>.jar}。 */
    private static String stagedName(String sourceName, String hash) {
        return baseName(sourceName) + "-" + hash + ".jar";
    }

    /** 源 jar 文件名去掉 {@code .jar} 后缀（清理旧副本时按它匹配前缀）。 */
    private static String baseName(String sourceName) {
        return sourceName.endsWith(".jar") ? sourceName.substring(0, sourceName.length() - 4) : sourceName;
    }

    /** 插件运行时副本所在的目录（{@code wikikernel.plugins.runtime-dir}，供日志 / 管理界面展示）。 */
    public Path runtimeDirectory() {
        return Path.of(properties.getRuntimeDir()).toAbsolutePath().normalize();
    }

    /** 卸载后尽力清理该插件不再被使用的副本（被其它实例占用时静默跳过）。 */
    private void deleteStagedCopy(String sourceName, String hash) {
        Path copy = runtimeDirectory().resolve(stagedName(sourceName, hash));
        try {
            Files.deleteIfExists(copy);
        } catch (IOException exception) {
            log.debug("插件副本暂无法清理（可能被其它实例占用）: {}", copy, exception);
        }
    }

    /** 清理同一插件名的旧副本：文件名形如 {@code <base>-<12 位哈希>.jar} 且不是当前副本才删。 */
    private void cleanupStaleCopies(Path runtimeDir, String base, Path keep) {
        try (Stream<Path> files = Files.list(runtimeDir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                boolean copy = name.startsWith(base + "-") && name.endsWith(".jar")
                        && name.length() == base.length() + 1 + 12 + 4;
                if (!copy || path.equals(keep)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.debug("旧插件副本暂无法清理（可能仍被加载）: {}", path, exception);
                }
            }
        } catch (IOException exception) {
            log.debug("扫描插件运行时目录失败: {}", runtimeDir, exception);
        }
    }

    /** 内容哈希（SHA-256 前 12 位十六进制）——运行时副本文件名与「源文件是否被替换」的判断依据。 */
    static String hashOf(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes), 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    /** 去掉 Properties 里键 / 值开头的 UTF-8 BOM（描述文件被带 BOM 的编辑器保存过时会出现）。 */
    private static void stripBom(Properties descriptor) {
        for (String name : List.copyOf(descriptor.stringPropertyNames())) {
            if (name.isEmpty() || name.charAt(0) != '\uFEFF') {
                continue;
            }
            String value = descriptor.getProperty(name, "");
            descriptor.remove(name);
            descriptor.setProperty(name.substring(1), value);
        }
    }


    /**
     * 从**运行时副本**里读取插件描述（{@code sourceName} 是源 jar 的文件名，用于日志 / API 展示）。
     */
    private List<DiscoveredPlugin> readFromJar(Path jar, String sourceName, PluginClassLoader classLoader)
            throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry descriptorEntry = jarFile.getJarEntry(PluginConstants.DESCRIPTOR_LOCATION);
            if (descriptorEntry != null) {
                Properties descriptor = new Properties();
                // Properties.load(InputStream) 按 ISO-8859-1 解码，这里显式用 UTF-8 Reader 以支持中文描述
                try (InputStream input = jarFile.getInputStream(descriptorEntry);
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    descriptor.load(reader);
                }

                // 描述文件可能带 UTF-8 BOM（编辑器 / PowerShell 写入），剥掉开头 BOM，否则第一行的键会变成 "﻿plugin.id"
                stripBom(descriptor);

                String id = descriptor.getProperty(PluginConstants.ID, "").trim();
                if (id.isEmpty()) {
                    log.warn("插件描述文件缺少 {}，已跳过: {}", PluginConstants.ID, jar);
                    return List.of();
                }

                PluginDescriptor pluginDescriptor = new PluginDescriptor(id,
                        descriptor.getProperty(PluginConstants.NAME),
                        descriptor.getProperty(PluginConstants.VERSION),
                        descriptor.getProperty(PluginConstants.DESCRIPTION),
                        parseIds(descriptor.getProperty(PluginConstants.REQUIRES)),
                        descriptor.getProperty(PluginConstants.REQUIRES_CORE, ""),
                        parseIds(descriptor.getProperty(PluginConstants.CLIENT_REQUIRES)),
                        descriptor.getProperty(PluginConstants.FEATURE_KEY, ""),
                        !"false".equalsIgnoreCase(
                                descriptor.getProperty(PluginConstants.FEATURE_DEFAULT, "true").trim()));

                String mainClass = descriptor.getProperty(PluginConstants.MAIN_CLASS, "").trim();
                if (!mainClass.isEmpty()) {
                    return List.of(new DiscoveredPlugin(pluginDescriptor,
                            loadEntryClass(mainClass, classLoader), null,
                            sourceName, classLoader));
                }

                // 描述文件没有入口类时，回退到标准 ServiceLoader 发现方式
                Supplier<WikiPlugin> existing = serviceLoaderInstance(classLoader);
                if (existing == null) {
                    return List.of();
                }
                return List.of(new DiscoveredPlugin(pluginDescriptor,
                        cast(entryClassOf(existing)), existing,
                        sourceName, classLoader));
            }

            // 没有描述文件时，同样回退到 ServiceLoader
            Supplier<WikiPlugin> existing = serviceLoaderInstance(classLoader);
            if (existing == null) {
                log.warn("jar 中没有找到插件描述文件或 SPI 声明: {}", jar);
                return List.of();
            }
            return List.of(new DiscoveredPlugin(existing.get().descriptor(),
                    cast(entryClassOf(existing)), existing,
                    sourceName, classLoader));
        }
    }

    /** 解析逗号分隔的插件 id 列表（描述文件里的 {@code plugin.requires} / {@code plugin.clientRequires}）。 */
    static List<String> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    private List<DiscoveredPlugin> discoverFromClasspath() {
        List<DiscoveredPlugin> result = new ArrayList<>();
        for (WikiPlugin plugin : ServiceLoader.load(WikiPlugin.class, getClass().getClassLoader())) {
            result.add(new DiscoveredPlugin(plugin.descriptor(), cast(plugin.getClass()),
                    () -> plugin, "classpath", null));
        }
        return result;
    }

    /** @return 是否启动成功（失败只记 ERROR，不影响其它插件与应用） */
    private boolean load(DiscoveredPlugin discovered) {
        PluginDescriptor descriptor = discovered.descriptor();
        ConfigurableApplicationContext springContext = null;
        try {
            Path dataDirectory = Path.of("data", "plugins", descriptor.id()).toAbsolutePath().normalize();
            Files.createDirectories(dataDirectory);

            Map<String, String> settings = properties.getSettings().getOrDefault(descriptor.id(), Map.of());
            SimplePluginContext context = new SimplePluginContext(
                    descriptor.id(), dataDirectory, new MapPluginConfig(settings));

            // 子 Spring 容器：插件注解生效、可注入 core Bean
            springContext = springContextFactory.create(descriptor.id(), discovered.entryClass(),
                    discovered.existing(), discovered.classLoader(), context);
            WikiPlugin plugin = springContext.getBean(WikiPlugin.class);

            // 插件自己的表（WikiPlugin.schemaEntities）：onStart 之前建表 / 补列，
            // 这样插件可以在 onStart / Bean 初始化里直接读写自己的表
            if (schemaDdl != null) {
                schemaDdl.ensureSchema(descriptor.id(), plugin.schemaEntities());
            }

            // 插件 Controller 动态挂到 core 的 MVC
            webRegistrar.register(descriptor.id(), springContext);

            plugin.onStart(context);

            plugins.add(new LoadedPlugin(descriptor, plugin, context,
                    discovered.source(), discovered.classLoader(), springContext));
            this.generation++;
            log.info("已启动插件 {} v{}{}（来源: {}）", descriptor.id(), descriptor.version(),
                    describeDependencies(descriptor), discovered.source());
            return true;
        } catch (Exception exception) {
            log.error("插件启动失败: {}", descriptor.id(), exception);
            closeQuietly(springContext);
            closeQuietly(discovered.classLoader());
            return false;
        }
    }

    /** 启动日志里的依赖摘要（依赖 / core 约束 / 客户端依赖），方便排查顺序与缺失问题。 */
    private static String describeDependencies(PluginDescriptor descriptor) {
        List<String> parts = new ArrayList<>();
        if (descriptor.hasRequires()) {
            parts.add("依赖: " + String.join(", ", descriptor.requires()));
        }
        if (!descriptor.coreConstraint().isAny()) {
            parts.add("core " + descriptor.coreConstraint().raw());
        }
        if (descriptor.hasClientRequires()) {
            parts.add("客户端依赖: " + String.join(", ", descriptor.clientRequires()));
        }
        return parts.isEmpty() ? "" : "（" + String.join("；", parts) + "）";
    }

    private boolean isEnabled(String pluginId) {
        return properties.getEnabled().isEmpty() || properties.getEnabled().contains(pluginId);
    }

    private Class<? extends WikiPlugin> loadEntryClass(String className, ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            if (!WikiPlugin.class.isAssignableFrom(type)) {
                throw new IllegalStateException("插件入口类没有实现 WikiPlugin: " + className);
            }
            return cast(type);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("找不到插件入口类: " + className, exception);
        }
    }

    private Supplier<WikiPlugin> serviceLoaderInstance(ClassLoader classLoader) {
        for (WikiPlugin plugin : ServiceLoader.load(WikiPlugin.class, classLoader)) {
            return () -> plugin;
        }
        return null;
    }

    private Class<?> entryClassOf(Supplier<WikiPlugin> existing) {
        return existing.get().getClass();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends WikiPlugin> cast(Class<?> type) {
        return (Class<? extends WikiPlugin>) type;
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException exception) {
            log.warn("关闭插件资源失败", exception);
        }
    }

    /** 发现阶段的候选插件：描述信息 + 入口类（+ classpath 插件的既有实例）。 */
    private record DiscoveredPlugin(PluginDescriptor descriptor,
                                    Class<? extends WikiPlugin> entryClass,
                                    Supplier<WikiPlugin> existing,
                                    String source,
                                    PluginClassLoader classLoader) {
    }
}
