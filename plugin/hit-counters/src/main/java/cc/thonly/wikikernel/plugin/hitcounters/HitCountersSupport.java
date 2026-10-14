package cc.thonly.wikikernel.plugin.hitcounters;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * HitCounters 公共工具：数字格式化（对应 MediaWiki 的 {@code Language::formatNum}）、安全解析与本地化文案。
 */
public final class HitCountersSupport {

    private HitCountersSupport() {
    }

    /** 按服务器语言格式化整数（1,234）。 */
    public static String format(long value) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(value);
    }

    public static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 取一条本地化文案（插件消息包 {@code i18n/messages*.properties} 里的键）；
     * 取不到时**显示键名本身**（与 core 的「漏翻显示键名」策略一致，不会回落到中文）。
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
