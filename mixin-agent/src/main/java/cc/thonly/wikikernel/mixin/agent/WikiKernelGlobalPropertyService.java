package cc.thonly.wikikernel.mixin.agent;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立启动器的全局属性服务。
 *
 * <p>Mixin 自带的 LaunchWrapper / ModLauncher Blackboard 都依赖对应宿主环境；
 * 纯 Java Agent 下需要提供这个最小实现，否则 Mixin 连子系统版本号都存不进去。</p>
 */
public final class WikiKernelGlobalPropertyService implements IGlobalPropertyService {

    private final Map<String, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    public <T> T getProperty(IPropertyKey key) {
        @SuppressWarnings("unchecked")
        T value = (T) properties.get(key.toString());
        return value;
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            properties.remove(key.toString());
        } else {
            properties.put(key.toString(), value);
        }
    }

    @Override
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        T value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = properties.get(key.toString());
        return value != null ? value.toString() : defaultValue;
    }

    private record Key(String name) implements IPropertyKey {
        @Override
        public String toString() {
            return name;
        }
    }
}
