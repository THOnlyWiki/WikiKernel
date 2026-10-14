package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 浏览器指纹登录插件的公共支撑：功能开关（core 站点设置 {@code features.fingerprint-login}，
 * **默认关闭**）、插件自己的配置（{@code PluginContext.config()}）、本地化文案与转义工具。
 * <p>
 * 插件自己的配置来自 {@code LocalSettings.yaml → wikikernel.plugins.settings.fingerprint-login}
 * （见 core 的 {@code PluginProperties.settings}），当前支持：
 * <pre>
 * fingerprint-login.https-only = true   # 只允许安全上下文（HTTPS / localhost）使用指纹登录
 * </pre>
 * 开关（{@code features.fingerprint-login}）由 core 的站点设置维护，插件通过 {@link SiteConfig} 读同一个键。
 */
@Component
public final class FingerprintLoginSupport {

    /** 功能开关键（core 的 {@code FeatureService.FINGERPRINT_LOGIN_KEY}，语义：默认关闭）。 */
    public static final String ENABLED_KEY = "features.fingerprint-login";

    /** 插件配置：只允许安全上下文（HTTPS / localhost）使用指纹登录。 */
    public static final String HTTPS_ONLY_KEY = "fingerprint-login.https-only";

    /** 指纹摘要的长度上限（实体列 {@code fingerprint_hash}；SHA-256 十六进制为 64）。 */
    public static final int HASH_MAX_LENGTH = 128;

    /** 指纹名称的长度上限（与实体列 {@code label} 一致）。 */
    public static final int LABEL_MAX_LENGTH = 100;

    /** UA 摘要的长度上限（与实体列 {@code user_agent} 一致）。 */
    public static final int USER_AGENT_MAX_LENGTH = 300;

    /** 时间显示的兜底格式（正常走 core 的 {@code format.dateTime} 键）。 */
    private static final String DEFAULT_TIME_PATTERN = "yyyy-MM-dd HH:mm";

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final Supplier<PluginConfig> pluginConfigSupplier;

    public FingerprintLoginSupport(SiteConfig siteConfig,
                                   MessageSource messageSource,
                                   Supplier<PluginConfig> pluginConfigSupplier) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.pluginConfigSupplier = pluginConfigSupplier;
    }

    /**
     * Spring 用的构造器：插件自己的配置来自子容器里的 {@link PluginContext}
     * （{@code PluginContext} 由 core 注册成 Bean，所以这里用 {@link ObjectProvider} 延迟取）。
     */
    @Autowired
    public FingerprintLoginSupport(SiteConfig siteConfig,
                                   MessageSource messageSource,
                                   ObjectProvider<PluginContext> pluginContextProvider) {
        this(siteConfig, messageSource, () -> {
            PluginContext context = pluginContextProvider == null ? null : pluginContextProvider.getIfAvailable();
            return context == null ? null : context.config();
        });
    }

    /** 是否启用浏览器指纹登录（默认关闭；站点设置 / LocalSettings.yaml 的 {@code wikikernel.features} 可开）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /** 是否只允许安全上下文（HTTPS / localhost）使用指纹登录（插件配置，默认关闭）。 */
    public boolean httpsOnly() {
        PluginConfig config = pluginConfig();
        if (config == null) {
            return false;
        }
        String value = config.get(HTTPS_ONLY_KEY, "");
        return "true".equalsIgnoreCase(value) || "1".equals(value)
                || "on".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    /** 插件私有配置（没有 PluginContext 时返回 null，例如单元测试 / 独立运行）。 */
    private PluginConfig pluginConfig() {
        return pluginConfigSupplier == null ? null : pluginConfigSupplier.get();
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
