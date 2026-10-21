package cc.thonly.wikikernel.mixin.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 启动期扫描插件目录：
 *
 * <ul>
 *     <li>读取 {@code META-INF/wiki-plugin.properties} 里的 {@code plugin.mixins}；</li>
 *     <li>没有显式声明时，自动发现插件根目录 / 子目录里的 {@code *.mixins.json} 或
 *         {@code mixins*.json}；</li>
 *     <li>把所有插件 jar 暂存到独立目录：带 Mixin 配置的插件通过 Spring Boot
 *         {@code PropertiesLauncher} 的 {@code loader.path} 加入应用类加载器，
 *         其余插件仅作为 Mixin 读取目标类字节码的资源副本。</li>
 * </ul>
 *
 * <p>只把声明了 Mixin 的插件放到应用类加载器，普通插件仍保留原有的
 * {@link cc.thonly.wikikernel.plugin.PluginClassLoader} 隔离和热加载能力。</p>
 */
final class MixinPluginScanner {

    private static final String DESCRIPTOR = "META-INF/wiki-plugin.properties";
    private static final String MIXINS = "plugin.mixins";

    record MixinConfigEntry(String pluginId, String resource, byte[] bytes) {
    }

    /**
     * @param pluginIds    启动期声明了 Mixin 配置的插件 id
     * @param loaderJars   需要进入应用类加载器的 Mixin 插件 jar（loader.path）
     * @param resourceJars 所有插件 jar 的暂存副本，供 Mixin 提前读取目标类字节码
     * @param configs      Mixin 配置
     */
    record ScanResult(Set<String> pluginIds,
                      List<Path> loaderJars,
                      List<Path> resourceJars,
                      List<MixinConfigEntry> configs) {
        boolean hasMixins() {
            return !configs.isEmpty();
        }
    }

    private MixinPluginScanner() {
    }

    static ScanResult scan(Path pluginDirectory, Path stageDirectory) throws IOException {
        if (!Files.isDirectory(pluginDirectory)) {
            return new ScanResult(Set.of(), List.of(), List.of(), List.of());
        }

        List<Path> sourceJars;
        try (Stream<Path> files = Files.list(pluginDirectory)) {
            sourceJars = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        Map<String, MixinConfigEntry> configs = new LinkedHashMap<>();

        Set<String> pluginIds = new LinkedHashSet<>();
        List<Path> loaderJars = new ArrayList<>();
        List<Path> resourceJars = new ArrayList<>();

        for (Path sourceJar : sourceJars) {
            String fallbackId = stripJarSuffix(sourceJar.getFileName().toString());
            String pluginId = fallbackId;
            List<String> declaredConfigs = List.of();
            Properties descriptor = new Properties();

            try (JarFile jar = new JarFile(sourceJar.toFile())) {
                JarEntry descriptorEntry = jar.getJarEntry(DESCRIPTOR);
                if (descriptorEntry != null) {
                    try (InputStream input = jar.getInputStream(descriptorEntry);
                         InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                        descriptor.load(reader);
                    }
                    stripBom(descriptor);
                    String id = descriptor.getProperty("plugin.id", "").trim();
                    if (!id.isEmpty()) {
                        pluginId = id;
                    }
                    declaredConfigs = parseList(descriptor.getProperty(MIXINS));
                }

                List<String> candidates = declaredConfigs.isEmpty()
                        ? autoDiscoverConfigs(jar)
                        : declaredConfigs;
                List<MixinConfigEntry> found = new ArrayList<>();
                for (String candidate : candidates) {
                    String resource = normalizeResource(candidate);
                    if (resource.isEmpty()) {
                        continue;
                    }
                    JarEntry entry = jar.getJarEntry(resource);
                    if (entry == null || entry.isDirectory()) {
                        System.err.println("[WikiKernel/Mixin] 插件 " + pluginId + " 声明的 Mixin 配置不存在：" + resource);
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream input = jar.getInputStream(entry)) {
                        bytes = input.readAllBytes();
                    }
                    if (bytes.length == 0) {
                        continue;
                    }
                    MixinConfigEntry previous = configs.get(resource);
                    if (previous != null) {
                        System.err.println("[WikiKernel/Mixin] Mixin 配置名冲突：" + resource
                                + "（已由 " + previous.pluginId() + " 使用，忽略 " + pluginId + "）");
                        continue;
                    }
                    MixinConfigEntry config = new MixinConfigEntry(pluginId, resource, bytes);
                    configs.put(resource, config);
                    found.add(config);
                }

                // 无论插件是否带 Mixin，都暂存一份供 Mixin 在目标插件加载前读取其类字节码。
                // 只有带 Mixin 配置的 jar 会进入 loader.path / 应用类加载器。
                Path staged = stageJar(sourceJar, stageDirectory);
                resourceJars.add(staged);
                if (!found.isEmpty()) {
                    loaderJars.add(staged);
                    pluginIds.add(pluginId);
                    System.out.println("[WikiKernel/Mixin] 发现插件 Mixin：" + pluginId + " -> "
                            + found.stream().map(MixinConfigEntry::resource).toList()
                            + "（暂存：" + staged + "）");
                }
            } catch (IOException | RuntimeException exception) {
                System.err.println("[WikiKernel/Mixin] 读取插件失败（已跳过）：" + sourceJar
                        + "，" + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }

        cleanupStageDirectory(stageDirectory, resourceJars);
        return new ScanResult(Set.copyOf(pluginIds), List.copyOf(loaderJars),
                List.copyOf(resourceJars), List.copyOf(configs.values()));
    }

    private static List<String> autoDiscoverConfigs(JarFile jar) {
        List<String> result = new ArrayList<>();
        jar.stream().map(JarEntry::getName).forEach(name -> {
            if (name.endsWith("/") || !name.endsWith(".json")) {
                return;
            }
            String fileName = name.substring(name.lastIndexOf('/') + 1);
            if (fileName.endsWith(".mixins.json") || (fileName.startsWith("mixins") && fileName.endsWith(".json"))) {
                result.add(name);
            }
        });
        result.sort(String::compareTo);
        return result;
    }

    private static Path stageJar(Path sourceJar, Path stageDirectory) throws IOException {
        Files.createDirectories(stageDirectory);
        byte[] bytes = Files.readAllBytes(sourceJar);
        String hash = sha256(bytes).substring(0, 12);
        String base = stripJarSuffix(sourceJar.getFileName().toString());
        Path target = stageDirectory.resolve(base + "-" + hash + ".jar");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path temp = stageDirectory.resolve(base + "-" + hash + ".jar.tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void cleanupStageDirectory(Path stageDirectory, List<Path> keep) {
        if (!Files.isDirectory(stageDirectory)) {
            return;
        }
        Set<Path> keepNames = keep.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        try (Stream<Path> files = Files.list(stageDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(path -> !keepNames.contains(path.toAbsolutePath().normalize()))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            System.err.println("[WikiKernel/Mixin] 旧暂存 jar 暂无法删除（可能被占用）：" + path);
                        }
                    });
        } catch (IOException exception) {
            System.err.println("[WikiKernel/Mixin] 清理暂存目录失败：" + stageDirectory + "，" + exception.getMessage());
        }
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeResource(String value) {
        String result = value.trim().replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static String stripJarSuffix(String name) {
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    private static void stripBom(Properties properties) {
        for (String key : List.copyOf(properties.stringPropertyNames())) {
            if (!key.isEmpty() && key.charAt(0) == '\uFEFF') {
                String value = properties.getProperty(key, "");
                properties.remove(key);
                properties.setProperty(key.substring(1), value);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
