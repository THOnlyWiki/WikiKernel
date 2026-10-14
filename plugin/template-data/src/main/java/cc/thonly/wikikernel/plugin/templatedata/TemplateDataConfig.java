package cc.thonly.wikikernel.plugin.templatedata;

import cc.thonly.wikikernel.common.plugin.PluginConfig;

import java.util.Locale;

/**
 * TemplateData 插件配置（对应 MediaWiki 的 {@code $wgLanguageCode} 等），在 {@code LocalSettings.yaml} 里：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       template-data:
 *         content-language: zh        # InterfaceText 的键（默认跟随系统语言：zh / en）
 *         generate-param-order: false # true = 规范化时自动补 paramOrder（非 MediaWiki 原版行为）
 * </pre>
 *
 * @param contentLanguage   站点内容语言（原版是 {@code $wgLanguageCode}）
 * @param generateParamOrder 规范化时补 {@code paramOrder}（原版不会补，默认关闭）
 */
public record TemplateDataConfig(String contentLanguage, boolean generateParamOrder) {

    public static TemplateDataConfig from(PluginConfig config) {
        String language = config.get("content-language", "");
        if (language == null || language.isBlank()) {
            language = TemplateDataMessages.defaultContentLanguage();
        }
        return new TemplateDataConfig(
                language.trim().toLowerCase(Locale.ROOT),
                config.getBoolean("generate-param-order", false));
    }
}
