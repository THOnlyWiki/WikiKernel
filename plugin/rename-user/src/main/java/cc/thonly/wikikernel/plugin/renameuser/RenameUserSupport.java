package cc.thonly.wikikernel.plugin.renameuser;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 重命名用户插件的公共支撑：插件文案（core 的 MessageSource 会先查 core 再查插件消息包）、
 * 站内页面路径与 HTML / 查询参数转义。
 * <p>
 * 权限语义由「重命名用户」用户组（组名与 core 的 {@code PermissionService.GROUP_RENAME_USER} 一致）决定，
 * 插件经自己的 {@code service.UserAdminStore} 查询 / 执行，这里不重复用户组常量。
 */
@Component
public final class RenameUserSupport {

    private final MessageSource messageSource;

    public RenameUserSupport(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 插件文案（键名与迁移前 core 一致）；找不到时显示翻译键本身，绝不回落中文。 */
    public String message(String key, String fallback, Object... args) {
        if (messageSource == null) {
            return fallback == null ? key : fallback;
        }
        String value = messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
        return value == null ? (fallback == null ? key : fallback) : value;
    }

    /** 插件文案（无参数、无兜底）：漏翻时显示翻译键本身。 */
    public String message(String key) {
        return message(key, null);
    }

    /** 时间显示：走消息键 {@code format.dateTime}（core 的本地化格式，与迁移前 {@code WikiTime} 一致）。 */
    public String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = message("format.dateTime", "yyyy-MM-dd HH:mm");
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    public static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }

    /** HTML 转义（插件拼链接 / 用户名时必须转义）。 */
    public static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    /** 站内页面路径：{@code 用户:甲} → {@code /wiki/%E7%94%A8%E6%88%B7:%E7%94%B2}（保留子页面的 {@code /}）。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return "/wiki/";
        }
        return "/wiki/" + Arrays.stream(title.split("/", -1))
                .map(RenameUserSupport::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /** 路径段编码（空格 → {@code %20}，不能用 {@code +}）。 */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}