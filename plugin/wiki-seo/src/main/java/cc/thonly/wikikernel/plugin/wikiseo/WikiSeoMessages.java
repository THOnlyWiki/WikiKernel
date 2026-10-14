package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 插件消息：WikiSEO 安装向导与插件名 / 描述的文案。
 * <p>
 * 文案在插件自己的资源文件里：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 键为 {@code plugin.wiki-seo.*}（插件名 / 描述，core 的 {@code PluginI18n} 用）与
 * {@code setup.wiki-seo.*}（安装向导的字段标签 / 说明）；漏翻的键由 core 的消息源回退成翻译键本身。
 */
public class WikiSeoMessages extends ResourceMessageBundle {

    /**
     * 取一条本地化文案；没有消息源或查不到时**显示键名本身**
     * （与 core 的「漏翻显示键名」策略一致，不会回落到中文）。
     */
    public static String text(MessageSource messageSource, String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, args.length == 0 ? null : args, key,
                    LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }
}
