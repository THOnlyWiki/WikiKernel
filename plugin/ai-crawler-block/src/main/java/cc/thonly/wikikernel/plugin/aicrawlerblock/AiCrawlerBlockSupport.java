package cc.thonly.wikikernel.plugin.aicrawlerblock;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/** 插件消息包与本地化文本工具。 */
final class AiCrawlerBlockSupport {

    private AiCrawlerBlockSupport() {
    }

    static String text(MessageSource messages, String key, String fallback) {
        if (messages == null) {
            return fallback;
        }
        try {
            String value = messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() || value.equals(key) ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}

/** 资源包入口：core 会自动合并 i18n/messages*.properties。 */
class AiCrawlerBlockMessages extends ResourceMessageBundle {
}
