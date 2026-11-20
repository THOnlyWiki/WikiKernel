package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 功能开关、界面文案和浏览器信息。 */
@Component
public final class FingerprintLoginSupport {

    /** 功能开关键（core 的 {@code FeatureService.FINGERPRINT_LOGIN_KEY}，语义：默认关闭）。 */
    public static final String ENABLED_KEY = "features.fingerprint-login";

    /** 指纹名称的长度上限（与实体列 {@code label} 一致）。 */
    public static final int LABEL_MAX_LENGTH = 100;

    /** UA 摘要的长度上限（与实体列 {@code user_agent} 一致）。 */
    public static final int USER_AGENT_MAX_LENGTH = 300;

    /** 时间显示的兜底格式（正常走 core 的 {@code format.dateTime} 键）。 */
    private static final String DEFAULT_TIME_PATTERN = "yyyy-MM-dd HH:mm";

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    public FingerprintLoginSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 是否启用浏览器指纹登录（默认关闭；站点设置 / LocalSettings.yaml 的 {@code wikikernel.features} 可开）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /**
     * 插件文案：取当前语言的本地化文案；**找不到时回显键名**（与 core 的约定一致，不写中文兜底）。
     */
    public String message(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        String value = messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
        return value == null ? key : value;
    }

    /** 时间显示（管理界面）：走 core 的 {@code format.dateTime}，空值返回空串。 */
    public String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = message("format.dateTime");
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern(DEFAULT_TIME_PATTERN));
        }
    }

    /** UA 摘要（管理界面展示用；超长截断，换行折叠成空格）。 */
    public static String userAgentSummary(String userAgent) {
        if (userAgent == null) {
            return "";
        }
        String flat = userAgent.replace('\r', ' ').replace('\n', ' ').trim();
        if (flat.length() <= USER_AGENT_MAX_LENGTH) {
            return flat;
        }
        return flat.substring(0, USER_AGENT_MAX_LENGTH - 1) + "…";
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    public static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }

    /** HTML 转义（插件拼 HTML 时必须转义）。 */
    public static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
