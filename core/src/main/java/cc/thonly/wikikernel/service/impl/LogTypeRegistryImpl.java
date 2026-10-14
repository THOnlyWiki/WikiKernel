package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.LogTypeRegistry;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LogTypeRegistryImpl implements LogTypeRegistry {

    private static final Comparator<LogType> ORDER =
            Comparator.comparingInt(LogType::order).thenComparing(LogType::id);

    private final List<LogType> coreTypes;
    private final PluginManager pluginManager;
    private final MessageSource messageSource;

    public LogTypeRegistryImpl(List<LogType> coreTypes,
                               PluginManager pluginManager,
                               MessageSource messageSource) {
        this.coreTypes = coreTypes;
        this.pluginManager = pluginManager;
        this.messageSource = messageSource;
    }

    @Override
    public List<LogType> all() {
        // 每次合并：插件在启动后才注册，不能缓存；core 优先，同名插件类型不覆盖
        Map<String, LogType> registry = new LinkedHashMap<>();
        coreTypes.stream().sorted(ORDER).forEach(type -> registry.putIfAbsent(type.id(), type));
        pluginManager.extensions(LogType.class).stream().sorted(ORDER)
                .forEach(type -> registry.putIfAbsent(type.id(), type));
        return List.copyOf(registry.values());
    }

    @Override
    public Optional<LogType> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(type -> id.equals(type.id())).findFirst();
    }

    @Override
    public String displayNameOf(String id) {
        LogType type = byId(id).orElse(null);
        String fallback = type == null ? id : type.displayName();
        return messageSource.getMessage("log.typeName." + id, null, fallback, LocaleContextHolder.getLocale());
    }
}
