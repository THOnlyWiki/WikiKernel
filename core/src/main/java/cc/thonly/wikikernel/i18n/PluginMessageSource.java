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
 * {@link MessageBundle}（插件消息，支持语言回退 zh-CN → zh → en → 任意）。
 * <p>
 * core 把它注册为 {@code messageSource} Bean（替代 Boot 默认实现），
 * 因此控制器 / 模板 {@code #{…}} / 邮件等处的解析都能拿到插件消息。
 */
public class PluginMessageSource implements MessageSource {

    private final ResourceBundleMessageSource base;
    private final ObjectProvider<PluginManager> pluginManagerProvider;

    public PluginMessageSource(ObjectProvider<PluginManager> pluginManagerProvider) {
        this.pluginManagerProvider = pluginManagerProvider;
        this.base = new ResourceBundleMessageSource();
        this.base.setBasenames("messages");
        this.base.setDefaultEncoding("UTF-8");
        this.base.setFallbackToSystemLocale(false);
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
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
                    return format(value, args);
                }
            }
        }
        return null;
    }

    /** 语言回退链：zh-CN → zh → en → 任意一个已有语言。 */
    private static List<String> localeFallback(Locale locale) {
        Set<String> languages = new LinkedHashSet<>();
        if (locale != null) {
            languages.add(locale.toString().replace('_', '-'));
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

    /** 语言回退链注释见上（zh-CN → zh → en → zh）。 */
    private static String format(String pattern, Object[] args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return new MessageFormat(pattern).format(args);
        } catch (IllegalArgumentException exception) {
            return pattern;
        }
    }
}
