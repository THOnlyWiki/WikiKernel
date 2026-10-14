package cc.thonly.wikikernel.plugin.usermerge;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 用户合并插件的公共支撑：插件文案（core 的 MessageSource 会先查 core 再查插件消息包）与
 * HTML 转义。
 * <p>
 * 权限语义由「核查员」用户组（组名与 core 的 {@code PermissionService.GROUP_CHECKUSER} 一致）决定，
 * 插件经自己的 {@code service.UserMergeStore} 查询 / 执行，这里不重复用户组常量。
 */
@Component
public final class UserMergeSupport {

    private final MessageSource messageSource;

    public UserMergeSupport(MessageSource messageSource) {
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
}