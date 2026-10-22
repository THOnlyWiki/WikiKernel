package cc.thonly.wikikernel.plugin.reverseproxy;

import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** 文案与前端脚本的公共小工具。 */
@Component
public class ReverseProxySupport {

    private final MessageSource messages;
    private final ReverseProxyConfigService config;

    public ReverseProxySupport(MessageSource messages, ReverseProxyConfigService config) {
        this.messages = messages;
        this.config = config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public String message(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 把文本编码成安全的 JavaScript 字符串字面量（也防止 {@code </script>} 截断）。 */
    public static String jsString(String value) {
        if (value == null) {
            return "''";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        out.append('\'');
        return out.toString();
    }
}
