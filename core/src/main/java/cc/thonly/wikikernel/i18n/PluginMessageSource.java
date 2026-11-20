package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.text.MessageFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 消息源：core 的 {@code messages*.properties} 优先，找不到时查插件注册的
 * {@link MessageBundle}（插件消息，依次查询地区、基础语言和默认翻译）。
 * <p>
 * core 把它注册为 {@code messageSource} Bean（替代 Boot 默认实现），
 * 因此控制器 / 模板 {@code #{…}} / 邮件等处的解析都能拿到插件消息。
 */
public class PluginMessageSource implements MessageSource {

    private final CoreMessages base;
    private final ObjectProvider<PluginManager> pluginManagerProvider;
    private final ObjectProvider<InterfaceMessageOverrides> overrides;

    public PluginMessageSource(ObjectProvider<PluginManager> pluginManagerProvider,
                               ObjectProvider<InterfaceMessageOverrides> overrides) {
        this.pluginManagerProvider = pluginManagerProvider;
        this.overrides = overrides;
        this.base = new CoreMessages();
        this.base.setBasenames("messages");
        this.base.setDefaultEncoding("UTF-8");
        this.base.setFallbackToSystemLocale(false);
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        String custom = customMessage(code, args, effective);
        if (custom != null) return custom;
        try {
            String core = base.getMessage(code, args, null, effective);
            if (core != null) {
                return core;
            }
        } catch (NoSuchMessageException ignored) {
            // 落到插件消息
        }
        String plugin = pluginMessage(code, args, effective);
        if (plugin != null) {
            return plugin;
        }
        // 没有本地化时**不回落中文**：默认值只有在「不是中文」时才用（例如版本名这类数据），
        // 否则直接显示翻译键本身，一眼就能看出漏翻
        if (isNonChineseFallback(defaultMessage)) {
            return defaultMessage;
        }
        return code;
    }

    /**
     * 查一条消息；**找不到返回 {@code null}**（不会回落成翻译键）。
     * 供「插件名 / 插件描述」这类需要判断「有没有本地化」的场景使用。
     */
    public String resolveOrNull(String code, Locale locale) {
        return resolveOrNull(code, null, locale);
    }

    /** 查一条带参数的消息；找不到返回 {@code null}。 */
    public String resolveOrNull(String code, Object[] args, Locale locale) {
        if (code == null) {
            return null;
        }
        Locale effective = locale == null ? Locale.getDefault() : locale;
        String custom = customMessage(code, args, effective);
        if (custom != null) return custom;
        try {
            String core = base.getMessage(code, args, null, effective);
            if (core != null) {
                return core;
            }
        } catch (NoSuchMessageException ignored) {
            // 落到插件消息
        }
        return pluginMessage(code, args, effective);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
        String value = getMessage(code, args, null, locale);
        if (value == null) {
            throw new NoSuchMessageException(code, locale);
        }
        return value;
    }

    @Override
    public String getMessage(org.springframework.context.MessageSourceResolvable resolvable, Locale locale)
            throws NoSuchMessageException {
        String[] codes = resolvable.getCodes();
        Object[] args = resolvable.getArguments();
        if (codes != null) {
            for (String code : codes) {
                String value = getMessage(code, args, null, locale);
                if (value != null) {
                    return value;
                }
            }
        }
        if (resolvable.getDefaultMessage() != null) {
            return resolvable.getDefaultMessage();
        }
        throw new NoSuchMessageException(codes == null || codes.length == 0 ? "" : codes[0], locale);
    }

    // ==================== 内部实现 ====================

    private String customMessage(String code, Object[] args, Locale locale) {
        if (code == null) return null;
        var store = overrides.getIfAvailable();
        var value = store == null ? null : store.find(code, locale);
        return value == null ? null : format(value.text(), args, locale);
    }

    /** Unformatted installed defaults, including active plugin bundles, with the same core-first precedence. */
    public Map<String, String> defaults(Locale locale) {
        Map<String, String> result = new java.util.TreeMap<>(base.raw(locale));
        var manager = pluginManagerProvider.getIfAvailable();
        if (manager != null) for (var bundle : manager.extensions(MessageBundle.class)) {
            for (String language : localeFallback(locale)) {
                var texts = bundle.messages().get(language);
                if (texts != null) texts.forEach(result::putIfAbsent);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static final class CoreMessages extends ResourceBundleMessageSource {
        Map<String, String> raw(Locale locale) {
            try {
                var bundle = getResourceBundle("messages", locale == null ? Locale.SIMPLIFIED_CHINESE : locale);
                Map<String, String> result = new java.util.TreeMap<>();
                for (String key : bundle.keySet()) result.put(key, bundle.getString(key));
                return result;
            } catch (java.util.MissingResourceException exception) { return Map.of(); }
        }
    }

    private String pluginMessage(String code, Object[] args, Locale locale) {
        PluginManager pluginManager = pluginManagerProvider.getIfAvailable();
        if (pluginManager == null || code == null) {
            return null;
        }
        List<String> fallback = localeFallback(locale);
        for (MessageBundle bundle : pluginManager.extensions(MessageBundle.class)) {
            Map<String, Map<String, String>> messages = bundle.messages();
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            for (String language : fallback) {
                Map<String, String> texts = messages.get(language);
                if (texts == null) {
                    continue;
                }
                String value = texts.get(code);
                if (value != null) {
                    return format(value, args, locale);
                }
            }
        }
        return null;
    }

    /** 先查完整语言标签及其规范地区，再查基础语言和默认翻译。 */
    private static List<String> localeFallback(Locale locale) {
        Set<String> languages = new LinkedHashSet<>();
        if (locale != null) {
            languages.add(locale.toLanguageTag());
            InterfaceLanguage supported = InterfaceLanguage.fromLocale(locale);
            if (supported != null) {
                languages.add(supported.getLocale().toLanguageTag());
            }
            languages.add(locale.getLanguage());
        }
        languages.add("en");
        // 插件资源文件的默认语言（i18n/messages.properties 的内容）
        languages.add("zh");
        return List.copyOf(languages);
    }

    /** 默认值是否「不是中文」（版本名这类数据可以保留；中文默认值是漏翻，要换成翻译键）。 */
    private static boolean isNonChineseFallback(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            Character character = value.charAt(index);
            if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    private static String format(String pattern, Object[] args, Locale locale) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return new MessageFormat(pattern, locale).format(args);
        } catch (IllegalArgumentException exception) {
            return pattern;
        }
    }
}
