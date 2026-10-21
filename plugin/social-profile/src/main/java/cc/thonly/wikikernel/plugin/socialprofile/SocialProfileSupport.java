package cc.thonly.wikikernel.plugin.socialprofile;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 用户交互插件的公共支撑：功能开关（{@code features.social-profile}，与 core 的站点设置同一个键）、
 * 插件配置（{@code wikikernel.plugins.settings.social-profile.*}，见 {@link #setting}）、
 * 插件文案（core 的 MessageSource 会先查 core 再查插件消息包）、HTML / JSON / 路径转义。
 */
@Component
public final class SocialProfileSupport {

    /** 功能开关键（迁移前 core 的 {@code FeatureService.SOCIAL_PROFILE_KEY}，语义不变）。 */
    public static final String ENABLED_KEY = "features.social-profile";

    /**
     * 旧功能开关键：插件改名（{@code user-interaction → social-profile}）之前用的是这个键。
     * <p>
     * 读取时仍然兼容（见 {@link #enabled()}）：新键没设置时回落到旧键，
     * 老实例的 LocalSettings.yaml / 站点设置里写的是 {@code features.user-interaction} 也不会被关掉；
     * 写回时统一用新键 {@link #ENABLED_KEY}。
     */
    public static final String LEGACY_ENABLED_KEY = "features.user-interaction";

    /** 插件配置键前缀（{@code wikikernel.plugins.settings.social-profile.*}）。 */
    public static final String PLUGIN_SETTINGS_PREFIX = "wikikernel.plugins.settings.social-profile.";

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final PluginContext pluginContext;

    public SocialProfileSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this(siteConfig, messageSource, null);
    }

    /**
     * @param siteConfig    core 的站点设置（{@code features.social-profile} 等；单元测试可传 null）
     * @param messageSource core 的消息源（已合并插件消息包；单元测试可传 null）
     * @param pluginContext 插件上下文（读 {@code wikikernel.plugins.settings.social-profile.*}；
     *                      由 core 的插件子容器注入，单元测试 / 独立运行时可传 null）
     */
    @Autowired
    public SocialProfileSupport(SiteConfig siteConfig, MessageSource messageSource,
                                  PluginContext pluginContext) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.pluginContext = pluginContext;
    }

    /** 「用户交互」是否启用（默认开启；站点设置 / LocalSettings.yaml 里可关闭）。 */
    public boolean enabled() {
        if (siteConfig == null) {
            return true;
        }
        // 改名兼容（user-interaction → social-profile）：新键优先，新键没设置时回落到旧键
        return siteConfig.getBoolean(ENABLED_KEY, siteConfig.getBoolean(LEGACY_ENABLED_KEY, true));
    }

    /** 插件自己的配置（{@code wikikernel.plugins.settings.social-profile.*}）；没有时返回 null。 */
    public PluginConfig pluginConfig() {
        return pluginContext == null ? null : pluginContext.config();
    }

    /**
     * 取一条插件配置：插件配置（{@code wikikernel.plugins.settings.social-profile.<key>}）
     * 优先，其次是站点设置（{@code settingsKey}，如 {@code socialProfile.points.boardPost}），
     * 都没有时返回 {@code defaultValue}。
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

    /** 取一条整数配置（非法值 / 缺省返回 {@code defaultValue}）。 */
    public int setting(String key, String settingsKey, int defaultValue) {
        String value = setting(key, settingsKey, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
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

    /**
     * 等级名（{@code socialProfile.level.<n>}）：有专门键就用它，否则用
     * {@code socialProfile.level.other} 带上等级号；两个键都没有时显示完整的翻译键。
     */
    public String levelName(int level) {
        String key = "socialProfile.level." + Math.max(level, 1);
        if (messageSource != null) {
            String value = messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return message("socialProfile.level.other", null, Math.max(level, 1));
    }

    /** 礼物名（{@code socialProfile.gift.<id>}；没有这个键时显示键本身）。 */
    public String giftName(String giftId) {
        return message("socialProfile.gift." + giftId);
    }

    /** 礼物说明（{@code socialProfile.gift.desc.<id>}；没有这个键时显示键本身）。 */
    public String giftDescription(String giftId) {
        return message("socialProfile.gift.desc." + giftId);
    }

    /** 奖励名（{@code socialProfile.award.<id>}；没有这个键时显示键本身）。 */
    public String awardName(String awardId) {
        return message("socialProfile.award." + awardId);
    }

    /** 给分规则（键 {@code socialProfile.points.*}，默认值见 {@link PointsRules}）。 */
    public PointsRules pointsRules() {
        int pageView = setting("points.pageView", "socialProfile.points.pageView",
                PointsRules.DEFAULT_PAGE_VIEW);
        int boardPost = setting("points.boardPost", "socialProfile.points.boardPost",
                PointsRules.DEFAULT_BOARD_POST);
        int friend = setting("points.friend", "socialProfile.points.friend",
                PointsRules.DEFAULT_FRIEND);
        int gift = setting("points.gift", "socialProfile.points.gift",
                PointsRules.DEFAULT_GIFT);
        int profileEdit = setting("points.profileEdit", "socialProfile.points.profileEdit",
                PointsRules.DEFAULT_PROFILE_EDIT);
        int cooldown = setting("points.viewCooldownSeconds", "socialProfile.points.viewCooldownSeconds",
                PointsRules.DEFAULT_VIEW_COOLDOWN_SECONDS);
        boolean enabled = setting("points.enabled", "socialProfile.points.enabled", true);
        String levels = setting("points.levels", "socialProfile.points.levels", null);
        return new PointsRules(enabled, pageView, boardPost, friend, gift, profileEdit, cooldown, levels);
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
        // 查询串必须留在路径编码之外：以前把「特殊:好友?tab=foes」整串当路径段编码，
        // 「?」被转义成 %3F，「=」被转义成 %3D，链接就变成了一个不存在的标题（404）。
        int query = title.indexOf('?');
        String path = query < 0 ? title : title.substring(0, query);
        String suffix = query < 0 ? "" : title.substring(query);
        return "/wiki/" + Arrays.stream(path.split("/", -1))
                .map(SocialProfileSupport::encodeSegment)
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
}
