package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 插件名 / 插件描述的本地化解析。
 * <p>
 * 约定：插件在自己的消息包（{@code i18n/messages.properties} + {@code i18n/messages_en.properties}…）
 * 里提供两个键（{@code <id>} 是插件 id）：
 *
 * <pre>
 * plugin.&lt;id&gt;.name=AI 代理
 * plugin.&lt;id&gt;.description=多厂商对话 + 只读工具 + 待确认提案
 * </pre>
 *
 * 解析顺序：**当前语言的消息键 → 描述文件里的原文（{@code wiki-plugin.properties}）→ 插件 id**；
 * 描述文件里的值现在是「技术名 + 版本」（由 {@code gradle.properties} 生成），不再是给人看的中文，
 * 所以无论哪个语言都不会漏出中文。
 * <p>
 * 「被仿造的原版扩展」的说明与官方页地址**不在插件里**（那些是原扩展的资料，不是本插件的文案），
 * 由 core 的 {@code version.extension.<id>.description} / {@code version.extension.<id>.url} 承载，
 * 见 {@code 特殊:版本}。
 */
@Component
public class PluginI18n {

    private final PluginMessageSource messageSource;

    public PluginI18n(PluginMessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 本地化插件名（找不到任何名字时返回插件 id）。 */
    public String name(PluginDescriptor descriptor) {
        return name(descriptor, LocaleContextHolder.getLocale());
    }

    /** 指定语言的本地化插件名。 */
    public String name(PluginDescriptor descriptor, Locale locale) {
        if (descriptor == null) {
            return "";
        }
        String localized = messageSource.resolveOrNull(key(descriptor.id(), "name"), locale);
        if (!Strings.isBlank(localized)) {
            return localized;
        }
        String raw = Strings.trimToEmpty(descriptor.name());
        return raw.isEmpty() ? descriptor.id() : raw;
    }

    /** 本地化插件描述（找不到时回落到描述文件里的原文，可能为空串）。 */
    public String description(PluginDescriptor descriptor) {
        return description(descriptor, LocaleContextHolder.getLocale());
    }

    /** 指定语言的本地化插件描述。 */
    public String description(PluginDescriptor descriptor, Locale locale) {
        if (descriptor == null) {
            return "";
        }
        String localized = messageSource.resolveOrNull(key(descriptor.id(), "description"), locale);
        if (!Strings.isBlank(localized)) {
            return localized;
        }
        return Strings.trimToEmpty(descriptor.description());
    }

    /** 消息键名（{@code plugin.<id>.name} / {@code plugin.<id>.description}）。 */
    public static String key(String pluginId, String field) {
        String id = Strings.trimToEmpty(pluginId);
        return "plugin." + (id.isEmpty() ? "unknown" : id) + "." + Strings.trimToEmpty(field);
    }
}
