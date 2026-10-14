package cc.thonly.wikikernel.common.plugin.support;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 扩展点注册表：按扩展点接口类型保存实现，线程安全。 */
public final class ExtensionRegistry {

    private final Map<Class<?>, List<Extension>> extensions = new ConcurrentHashMap<>();

    public <T extends Extension> void register(Class<T> extensionPoint, T extension) {
        Objects.requireNonNull(extensionPoint, "extensionPoint");
        Objects.requireNonNull(extension, "extension");
        this.extensions.computeIfAbsent(extensionPoint, key -> new CopyOnWriteArrayList<>()).add(extension);
    }

    public <T extends Extension> List<T> lookup(Class<T> extensionPoint) {
        List<Extension> registered = this.extensions.get(extensionPoint);
        if (registered == null || registered.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(registered.size());
        for (Extension extension : registered) {
            result.add(extensionPoint.cast(extension));
        }
        return List.copyOf(result);
    }
}
