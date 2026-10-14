package cc.thonly.wikikernel.plugin.templatedata;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 插件消息：TemplateData 的界面消息（{@code zh} / {@code en} 两套；WikiKernel 没有 MediaWiki 的消息系统，
 * 原版 {@code i18n/*.json} 里的 200 多种语言不移植）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 本类同时是注册给 core 的 {@code MessageBundle}，所以 {@code plugin.template-data.*} 与
 * {@code templatedata-*} 这些键在 core 的消息源里也能查到。
 * <p>
 * 语言选择：{@link #forCurrentLocale()} 取 {@code Locale.getDefault().getLanguage()}，
 * 主语言是 {@code zh} 的用中文，其余（含未知语言）用英文；中文表缺键时回落到英文表，还缺就显示键名。
 */
public final class TemplateDataMessages extends ResourceMessageBundle {

    private final String language;
    private final Map<String, String> primary;
    private final Map<String, String> fallback;

    /** 默认构造：按 JVM 默认语言（{@code context.register(MessageBundle.class, new TemplateDataMessages())} 用）。 */
    public TemplateDataMessages() {
        this("zh".equals(primaryLanguage(Locale.getDefault().getLanguage())) ? "zh" : "en");
    }

    private TemplateDataMessages(String language) {
        this.language = language;
        Map<String, Map<String, String>> all = messages();
        this.primary = all.getOrDefault(language, Map.of());
        this.fallback = all.getOrDefault("zh".equals(language) ? "en" : "zh", Map.of());
    }

    /** 按系统语言（{@code Locale.getDefault()}）取消息表。 */
    public static TemplateDataMessages forCurrentLocale() {
        return of(Locale.getDefault().getLanguage());
    }

    /** 按语言代码取消息表：主语言为 {@code zh} 用中文，其余用英文。 */
    public static TemplateDataMessages of(String languageCode) {
        return new TemplateDataMessages(
                "zh".equals(primaryLanguage(languageCode)) ? "zh" : "en");
    }

    /** 界面语言代码（{@code zh} / {@code en}）。 */
    public String language() {
        return language;
    }

    /** 取消息原文；找不到时返回键名本身（原版会显示 {@code ⧼key⧽}）。 */
    public String text(String key) {
        return text(key, List.of());
    }

    /** 取消息并替换 {@code $1}、{@code $2}… 参数。 */
    public String text(String key, List<String> params) {
        String message = primary.get(key);
        if (message == null) {
            message = fallback.get(key);
        }
        if (message == null) {
            message = key;
        }
        for (int i = 0; i < params.size(); i++) {
            String value = params.get(i) == null ? "" : params.get(i);
            message = message.replace("$" + (i + 1), value);
        }
        return message;
    }

    /**
     * InterfaceText 的站点内容语言（原版是 {@code $wgLanguageCode}，WikiKernel 没有该配置，
     * 插件用 {@code content-language} 覆盖，默认跟随系统语言：中文环境 {@code zh}，其余 {@code en}）。
     */
    public static String defaultContentLanguage() {
        return primaryLanguage(Locale.getDefault().getLanguage());
    }

    /** 取语言代码的主语言（{@code zh-hans} → {@code zh}、{@code en-gb} → {@code en}）。 */
    static String primaryLanguage(String languageCode) {
        if (languageCode == null) {
            return "en";
        }
        String normalized = languageCode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = normalized.indexOf('-');
        return dash > 0 ? normalized.substring(0, dash) : normalized;
    }
}
