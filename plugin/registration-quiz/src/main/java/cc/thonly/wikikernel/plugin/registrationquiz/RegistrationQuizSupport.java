package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizMessages;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 注册问答的本地化文案：core 的 {@code MessageSource} 已合并插件消息（{@link RegistrationQuizMessages}），
 * 缺失时**显示翻译键本身**（与 core 的「漏翻显示键名」策略一致，不会回落到中文）。
 */
public class RegistrationQuizSupport {

    private final MessageSource messageSource;

    public RegistrationQuizSupport(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 取当前语言的文案（core 的 MessageSource 已合并插件消息；缺失时显示键名）。 */
    public String text(String key) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }
}
