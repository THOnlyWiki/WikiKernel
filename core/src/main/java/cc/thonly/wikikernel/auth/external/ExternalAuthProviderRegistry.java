package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 第三方登录 provider 注册表。
 * <p>
 * provider 是插件扩展点：core 只保留流程与一个空的 Spring List（方便测试 / 未来内置 provider），
 * 实际 GitHub / QQ 等 provider 由插件在 {@code onStart} 里注册，登录页和设置页动态读取。
 */
@Component
public class ExternalAuthProviderRegistry {

    private final List<ExternalAuthProvider> coreProviders;
    private volatile PluginManager pluginManager;

    public ExternalAuthProviderRegistry(List<ExternalAuthProvider> coreProviders) {
        this.coreProviders = coreProviders == null ? List.of() : List.copyOf(coreProviders);
    }

    /** 插件管理器由 Spring 注入；单元测试里没有时可省略。 */
    @Autowired(required = false)
    public void setPluginManager(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 当前已加载 provider（core + 插件），按 order / id 排序并去重。 */
    public List<ExternalAuthProvider> all() {
        Map<String, ExternalAuthProvider> merged = new LinkedHashMap<>();
        for (ExternalAuthProvider provider : coreProviders) {
            merged.put(provider.id(), provider);
        }
        PluginManager manager = this.pluginManager;
        if (manager != null) {
            for (ExternalAuthProvider provider : manager.extensions(ExternalAuthProvider.class)) {
                merged.putIfAbsent(provider.id(), provider);
            }
        }
        List<ExternalAuthProvider> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingInt(ExternalAuthProvider::order)
                .thenComparing(ExternalAuthProvider::id));
        return List.copyOf(result);
    }

    public Optional<ExternalAuthProvider> find(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(provider -> provider.id().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    /** 重复 provider id 时返回其中一个（用于设置页 / 启动排查）。 */
    public Optional<String> duplicateIds() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ExternalAuthProvider provider : all()) {
            counts.merge(provider.id(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
