package cc.thonly.wikikernel.mixin.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Agent 启动期收集的插件资源。Mixin 读配置 / Mixin 类字节码时优先走这里，
 * 这样即使 Spring Boot 的应用类加载器尚未建立，也能完成配置解析。
 */
final class MixinResources {

    private static volatile List<Path> jars = List.of();
    private static volatile Map<String, byte[]> resources = Map.of();
    private static volatile URL[] classPath = new URL[0];

    private MixinResources() {
    }

    static void install(List<Path> stagedJars, List<MixinPluginScanner.MixinConfigEntry> configs) {
        Map<String, byte[]> resourceMap = new LinkedHashMap<>();
        for (MixinPluginScanner.MixinConfigEntry config : configs) {
            resourceMap.put(config.resource(), config.bytes());
            resourceMap.put("/" + config.resource(), config.bytes());
        }
        resources = Map.copyOf(resourceMap);
        jars = List.copyOf(stagedJars);
        classPath = stagedJars.stream().map(path -> {
            try {
                return path.toUri().toURL();
            } catch (Exception exception) {
                throw new IllegalStateException("插件 Mixin jar 路径无效: " + path, exception);
            }
        }).toArray(URL[]::new);
    }

    static URL[] classPath() {
        return classPath.clone();
    }

    static InputStream open(String name) {
        byte[] bytes = resources.get(name);
        if (bytes == null && name.startsWith("/")) {
            bytes = resources.get(name.substring(1));
        }
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    static byte[] readClass(String binaryName) {
        String entryName = binaryName.replace('.', '/') + ".class";
        byte[] fromJar = readEntry(entryName);
        if (fromJar != null) {
            return fromJar;
        }
        return null;
    }

    static InputStream openEntryAsStream(String entryName) {
        byte[] bytes = readEntry(entryName.startsWith("/") ? entryName.substring(1) : entryName);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    static byte[] readEntry(String entryName) {
        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry != null && !entry.isDirectory()) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        return input.readAllBytes();
                    }
                }
            } catch (IOException exception) {
                System.err.println("[WikiKernel/Mixin] 读取暂存插件失败（已跳过）：" + jarPath
                        + "，" + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }
        return null;
    }
}
