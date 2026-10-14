package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EmailTypeRegistryImpl implements EmailTypeRegistry {

    private static final Comparator<EmailType> ORDER =
            Comparator.comparingInt(EmailType::order).thenComparing(EmailType::id);

    private final List<EmailType> coreTypes;
    private final PluginManager pluginManager;
    private final MessageSource messageSource;

    public EmailTypeRegistryImpl(List<EmailType> coreTypes,
                                 PluginManager pluginManager,
                                 MessageSource messageSource) {
        this.coreTypes = coreTypes;
        this.pluginManager = pluginManager;
        this.messageSource = messageSource;
    }

    @Override
    public List<EmailType> all() {
        // 每次合并：插件在启动后才注册，不能缓存；core 优先，同名插件类型不覆盖
        Map<String, EmailType> registry = new LinkedHashMap<>();
        coreTypes.stream().sorted(ORDER).forEach(type -> registry.putIfAbsent(type.id(), type));
        pluginManager.extensions(EmailType.class).stream().sorted(ORDER)
                .forEach(type -> registry.putIfAbsent(type.id(), type));
        return List.copyOf(registry.values());
    }

    @Override
    public Optional<EmailType> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(type -> id.equals(type.id())).findFirst();
    }

    @Override
    public String displayNameOf(String id) {
        EmailType type = byId(id).orElse(null);
        String fallback = type == null ? id : type.displayName();
        return messageSource.getMessage("email.typeName." + id, null, fallback, LocaleContextHolder.getLocale());
    }
}
