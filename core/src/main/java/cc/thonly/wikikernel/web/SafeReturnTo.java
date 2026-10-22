package cc.thonly.wikikernel.web;

import java.util.Locale;

/**
 * 登录 / 改密等流程里 {@code returnto} 的安全过滤。
 *
 * <p>只允许站内绝对路径。浏览器会把反斜杠当作路径分隔符（{@code /\evil.example}
 * 会被解析成协议相对地址），所以反斜杠、控制字符以及常见的百分号编码绕过
 * （{@code %5c}、{@code %2f}、{@code %0d/%0a/%00}）全部拒绝。这里不信任客户端
 * 的 JS 校验，服务端始终以本类为准。</p>
 */
public final class SafeReturnTo {

    private static final int MAX_LENGTH = 512;

    private SafeReturnTo() {
    }

    /** 只接受站内绝对路径；非法值返回 fallback。 */
    public static String path(String value, String fallback) {
        String candidate = normalize(value);
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH
                || !candidate.startsWith("/") || candidate.startsWith("//")
                || unsafe(candidate)) {
            return fallback;
        }
        return candidate;
    }

    /**
     * 改密页兼容“纯标题”参数（如 {@code 特殊:参数设置}）：
     * 纯标题会补成 {@code /wiki/<标题>}；路径、协议地址、反斜杠与控制字符按
     * {@link #path(String, String)} 的规则处理。
     */
    public static String titleOrPath(String value, String fallback) {
        String candidate = normalize(value);
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return fallback;
        }
        if (candidate.startsWith("/")) {
            return path(candidate, fallback);
        }
        if (candidate.contains("://") || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0 || unsafe(candidate)
                || candidate.indexOf('?') >= 0 || candidate.indexOf('#') >= 0) {
            return fallback;
        }
        return "/wiki/" + candidate;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean unsafe(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '\r' || current == '\n'
                    || current < 0x20 || current == 0x7f) {
                return true;
            }
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("%5c") || lower.contains("%2f")
                || lower.contains("%0d") || lower.contains("%0a")
                || lower.contains("%00") || lower.contains("%09");
    }
}
