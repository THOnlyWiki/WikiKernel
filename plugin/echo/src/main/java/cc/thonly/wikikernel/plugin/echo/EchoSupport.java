package cc.thonly.wikikernel.plugin.echo;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Echo 插件的公共支撑：功能开关（{@code features.echo}，与 core 的站点设置同一个键）、
 * 插件配置（{@code wikikernel.plugins.settings.echo.*}，见 {@link #setting}）、
 * 插件文案（core 的 MessageSource 会先查 core 再查插件消息包）、HTML / JSON / 路径转义、
 * 时间显示、用户讨论页 / 用户页判定。
 */
@Component
public final class EchoSupport {

    /** 功能开关键。 */
    public static final String ENABLED_KEY = EchoKeys.ENABLED_KEY;

    /** 插件配置键前缀（{@code wikikernel.plugins.settings.echo.*}）。 */
    public static final String PLUGIN_SETTINGS_PREFIX = "wikikernel.plugins.settings.echo.";

    /** 用户讨论页命名空间前缀（当前语言是中文的站点；判定时按前缀匹配）。 */
    private static final String[] USER_TALK_PREFIXES = {"用户讨论:", "User talk:"};

    /** 「用户:」命名空间前缀（提及判定用）。 */
    private static final String[] USER_PREFIXES = {"用户:", "User:"};

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final PluginContext pluginContext;

    public EchoSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this(siteConfig, messageSource, null);
    }

    /**
     * @param siteConfig    core 的站点设置（{@code features.echo} 等；单元测试可传 null）
     * @param messageSource core 的消息源（已合并插件消息包；单元测试可传 null）
     * @param pluginContext 插件上下文（读 {@code wikikernel.plugins.settings.echo.*}；
     *                      由 core 的插件子容器注入，单元测试 / 独立运行时可传 null）
     */
    @Autowired
    public EchoSupport(SiteConfig siteConfig, MessageSource messageSource, PluginContext pluginContext) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.pluginContext = pluginContext;
    }

    /** Echo 是否启用（默认开启；站点设置 / LocalSettings.yaml 里可关闭）。 */
    public boolean enabled() {
        if (siteConfig == null) {
            return true;
        }
        return siteConfig.getBoolean(ENABLED_KEY, true);
    }

    /** 插件自己的配置（{@code wikikernel.plugins.settings.echo.*}）；没有时返回 null。 */
    public PluginConfig pluginConfig() {
        return pluginContext == null ? null : pluginContext.config();
    }

    /** 站点级开关：是否禁用「讨论页被编辑」通知（默认不禁用）。 */
    public boolean disableTalk() {
        return siteConfig != null && siteConfig.getBoolean("echo.site.disableTalk", false);
    }

    /** 站点级开关：是否禁用「@提及」通知（默认不禁用）。 */
    public boolean disableMention() {
        return siteConfig != null && siteConfig.getBoolean("echo.site.disableMention", false);
    }

    /** 站点级开关：是否禁用「被回退」通知（默认不禁用）。 */
    public boolean disableRevert() {
        return siteConfig != null && siteConfig.getBoolean("echo.site.disableRevert", false);
    }

    /** 站点级开关：是否禁用「新用户欢迎」通知（默认不禁用）。 */
    public boolean disableWelcome() {
        return siteConfig != null && siteConfig.getBoolean("echo.site.disableWelcome", false);
    }

    /**
     * 取一条插件配置：插件配置（{@code wikikernel.plugins.settings.echo.<key>}）
     * 优先，其次是站点设置（{@code settingsKey}），都没有时返回 {@code defaultValue}。
     */
    public String setting(String key, String settingsKey, String defaultValue) {
        PluginConfig config = pluginConfig();
        if (config != null) {
            String value = config.get(key, null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        if (siteConfig != null && settingsKey != null) {
            String value = siteConfig.get(settingsKey, null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return defaultValue;
    }

    /** 取一条开关配置（{@code true} / {@code 1} / {@code on} / {@code yes} 视为真）。 */
    public boolean setting(String key, String settingsKey, boolean defaultValue) {
        String value = setting(key, settingsKey, null);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    /** 插件文案（键名 {@code echo.*}）；找不到时显示翻译键本身，绝不回落中文。 */
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

    /** 站内页面路径：{@code 用户:甲} → {@code /wiki/%E7%94%A8%E6%88%B7:%E7%94%B2}（保留子页面的 {@code /}）。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return "/wiki/";
        }
        int query = title.indexOf('?');
        String path = query < 0 ? title : title.substring(0, query);
        String suffix = query < 0 ? "" : title.substring(query);
        return "/wiki/" + Arrays.stream(path.split("/", -1))
                .map(EchoSupport::encodeSegment)
                .collect(Collectors.joining("/"))
                + suffix;
    }

    /** 查询参数值编码（空格 → {@code +}，与 Thymeleaf 的链接表达式一致）。 */
    public static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 路径段编码（空格 → {@code %20}，不能用 {@code +}）。 */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 是否用户讨论页（{@code 用户讨论:甲} / {@code User talk:甲}）。 */
    public static boolean isUserTalk(String title) {
        return startsWithAny(title, USER_TALK_PREFIXES);
    }

    /** 是否用户页（{@code 用户:甲} / {@code User:甲}）。 */
    public static boolean isUserPage(String title) {
        return startsWithAny(title, USER_PREFIXES);
    }

    /** 从用户讨论页标题取用户名（{@code 用户讨论:甲/子页} → {@code 甲}）。 */
    public static String userNameFromTalk(String title) {
        if (title == null) {
            return null;
        }
        for (String prefix : USER_TALK_PREFIXES) {
            if (title.startsWith(prefix)) {
                String rest = title.substring(prefix.length());
                int slash = rest.indexOf('/');
                return slash < 0 ? rest : rest.substring(0, slash);
            }
        }
        return null;
    }

    /** 从用户页标题取用户名（{@code 用户:甲/子页} → {@code 甲}）。 */
    public static String userNameFromUserPage(String title) {
        if (title == null) {
            return null;
        }
        for (String prefix : USER_PREFIXES) {
            if (title.startsWith(prefix)) {
                String rest = title.substring(prefix.length());
                int slash = rest.indexOf('/');
                return slash < 0 ? rest : rest.substring(0, slash);
            }
        }
        return null;
    }

    /** 时间显示：走消息键 {@code format.dateTime}（core 的本地化格式）。 */
    public String formatTime(LocalDateTime time) {
        String pattern = message(EchoKeys.FORMAT_TIME_KEY, "yyyy-MM-dd HH:mm");
        if (time == null) {
            return "";
        }
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    /**
     * 站点基础地址（拼邮件里的绝对链接用）：站点设置 {@code echo.email.baseUrl} 优先
     * （反代 / 多域名场景），否则用当前请求的 scheme + host + 非默认端口；都不行时返回空串
     * （邮件里退化成站内相对路径）。
     */
    public String baseUrl() {
        String configured = siteConfig == null ? null : siteConfig.get(EchoKeys.EMAIL_BASE_URL_KEY, null);
        if (configured != null && !configured.isBlank()) {
            String value = configured.trim();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
        try {
            org.springframework.web.context.request.RequestAttributes attributes =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                jakarta.servlet.http.HttpServletRequest request = servlet.getRequest();
                String scheme = request.getScheme();
                int port = request.getServerPort();
                boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                        || ("https".equalsIgnoreCase(scheme) && port == 443);
                return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
            }
        } catch (RuntimeException exception) {
            // 没有请求上下文（后台线程 / 定时任务）：退化相对路径
        }
        return "";
    }

    private static boolean startsWithAny(String title, String[] prefixes) {
        if (title == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (title.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}