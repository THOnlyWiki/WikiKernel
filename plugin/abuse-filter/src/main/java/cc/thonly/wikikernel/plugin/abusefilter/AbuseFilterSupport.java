package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 滥用过滤器插件的公共支撑：功能开关（{@code features.abuse-filter}，与 core 站点设置同一个键）、
 * 插件文案（core 的 MessageSource 会先查 core 再查插件消息包）、时间格式化、HTML / JSON / 路径转义，
 * 以及「管理员 / 行政员」判断（core 的 {@code PermissionService.canManage} 语义）。
 */
@Component
public final class AbuseFilterSupport {

    /** 功能开关键（迁移前 core 的 {@code FeatureService.ABUSE_FILTER_KEY}，语义不变：默认关闭）。 */
    public static final String ENABLED_KEY = "features.abuse-filter";

    /** 管理员组（core 的 {@code PermissionService.GROUP_ADMIN}）。 */
    public static final String GROUP_ADMIN = "管理员";
    /** 行政员组（core 的 {@code PermissionService.GROUP_BUREAUCRAT}）。 */
    public static final String GROUP_BUREAUCRAT = "行政员";
    /** 「机器人」组（系统账号「滥用过滤器」属于它）。 */
    public static final String GROUP_BOT = "机器人";
    /** 「自动确定用户」组（{@code blockautopromote} 会移除它）。 */
    public static final String GROUP_AUTOCONFIRMED = "自动确定用户";

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public AbuseFilterSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 「滥用过滤器」是否启用（默认关闭；可在 /init、站点设置或 LocalSettings.yaml 里开启）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /** 插件文案（键名与迁移前 core 一致）；没有 MessageSource（独立运行 / 单元测试）时返回兜底文案。 */
    public String message(String key, String fallback, Object... args) {
        if (messageSource == null) {
            return fallback == null ? key : fallback;
        }
        String value = messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
        return value == null ? (fallback == null ? key : fallback) : value;
    }

    /** 插件文案（无参数、无兜底）：漏翻时显示翻译键本身，绝不回落中文。 */
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

    /** 是否管理员 / 行政员（特殊页与 API 的管理权限，与迁移前 {@code PermissionService.canManage} 一致）。 */
    public static boolean isManager(WikiUser user, UserStore userStore) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return false;
        }
        Set<String> groups = userStore == null ? Set.of() : userStore.groups(user.getId());
        return groups.contains(GROUP_ADMIN) || groups.contains(GROUP_BUREAUCRAT);
    }

    /** 过滤器说明的拼接（命中多个时用当地语言的分隔符分隔）。 */
    public String join(List<String> values) {
        return values == null ? "" : String.join(message("abusefilter.listSeparator"), values);
    }

    /** 命中 {@code disallow} 的提示文案（键名与迁移前 core 一致）。 */
    public String disallowedText(List<String> messages) {
        return message("abusefilter.disallowed", null, join(messages));
    }

    /** 命中 {@code warn} / {@code degrade} 的提示文案（再次确认的说明）。 */
    public String warnText(List<String> warnings) {
        return message("abusefilter.warnTitle") + " " + join(warnings) + " "
                + message("abusefilter.warnConfirm");
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
                .map(AbuseFilterSupport::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /** 查询参数值编码（空格 → {@code +}）。 */
    public static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 路径段编码（空格 → {@code %20}，不能用 {@code +}）。 */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
