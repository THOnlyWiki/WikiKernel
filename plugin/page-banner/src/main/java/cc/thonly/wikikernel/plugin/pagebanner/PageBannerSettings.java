package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.service.SiteConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * 页面横幅设置：读取站点设置（{@code wiki_setting}；表单由插件自己注册的设置分区在
 * 「特殊:权限配置 → 站点设置」里渲染，安装向导里是本插件的 SetupStep tab），
 * 以及插件自己的配置（{@code application.yaml} 的 {@code wikikernel.plugins.settings.page-banner.*}）。
 * <p>
 * 这里只**读**设置：站点设置键沿用迁移前的 {@code features.page-banner.*}（core 里已经没有页面横幅代码）：
 * <ul>
 *     <li>{@code features.page-banner}：是否启用页面横幅（默认开启）；</li>
 *     <li>{@code features.page-banner.files}：横幅图片池（{@code |} 或换行分隔的文件页名称；轮播按这里的顺序）；</li>
 *     <li>{@code features.page-banner.whitelist-enabled}：白名单模式（默认开启）；</li>
 *     <li>{@code features.page-banner.whitelist}：白名单页面标题；</li>
 *     <li>{@code features.page-banner.rotation}：轮换机制（stable / random / rotate / daily / hourly）；</li>
 *     <li>{@code features.page-banner.per-page}：逐页配置（每行 {@code 页面标题 = 横幅文件名}，文件名可省，
 *     多个文件名用 {@code |} 分隔 = 该页轮播的固定顺序）。</li>
 * </ul>
 * 轮播（自动翻页 / 过渡 / 控件 / 悬停暂停）的设置键与默认值：
 * <ul>
 *     <li>{@code features.page-banner.interval}（插件配置里是 {@code interval}）：自动翻页间隔秒数，默认 {@code 5}，
 *     {@code 0}（或负数 / 非法值）= 不自动翻页；</li>
 *     <li>{@code features.page-banner.transition}（{@code transition}）：{@code fade}（默认）/ {@code slide}，非法值回退 {@code fade}；</li>
 *     <li>{@code features.page-banner.controls}（{@code controls}）：是否显示上一张 / 下一张按钮与指示点，默认 {@code true}
 *     （只有一张图片时无论如何都不显示控件）；</li>
 *     <li>{@code features.page-banner.pause-on-hover}（{@code pause-on-hover}）：鼠标悬停是否暂停自动翻页，默认 {@code true}。</li>
 * </ul>
 * 读取顺序：**插件配置**（短键 {@code interval} / {@code transition} / {@code controls} / {@code pause-on-hover}）优先，
 * 其次是站点设置（{@code features.page-banner.*}），都没有时用默认值。
 * <p>
 * 逐页配置 / 白名单的比对语义与迁移前的 {@code PageBannerServiceImpl} 保持一致（白名单同时比对原始标题与规范化标题，
 * 逐页配置里出现过的页面自动视为「要显示横幅」）。
 */
public final class PageBannerSettings {

    /** 站点设置键：是否启用页面横幅（默认开启）——与 core 的 {@code PageBannerService.ENABLED_KEY} 一致。 */
    public static final String ENABLED_KEY = "features.page-banner";
    /** 站点设置键：横幅图片池——与 core 的 {@code PageBannerService.FILES_KEY} 一致。 */
    public static final String FILES_KEY = "features.page-banner.files";
    /** 站点设置键：白名单模式——与 core 的 {@code PageBannerService.WHITELIST_ENABLED_KEY} 一致。 */
    public static final String WHITELIST_ENABLED_KEY = "features.page-banner.whitelist-enabled";
    /** 站点设置键：白名单页面——与 core 的 {@code PageBannerService.WHITELIST_KEY} 一致。 */
    public static final String WHITELIST_KEY = "features.page-banner.whitelist";
    /** 站点设置键：轮换机制——与 core 的 {@code PageBannerService.ROTATION_KEY} 一致。 */
    public static final String ROTATION_KEY = "features.page-banner.rotation";
    /** 站点设置键：逐页配置——与 core 的 {@code PageBannerService.PER_PAGE_KEY} 一致。 */
    public static final String PER_PAGE_KEY = "features.page-banner.per-page";

    /** 站点设置键：自动翻页间隔秒数（默认 {@value #DEFAULT_INTERVAL}；{@code 0} = 不自动翻页）。 */
    public static final String INTERVAL_KEY = "features.page-banner.interval";
    /** 站点设置键：过渡效果（{@code fade} / {@code slide}，默认 {@code fade}）。 */
    public static final String TRANSITION_KEY = "features.page-banner.transition";
    /** 站点设置键：是否显示翻页控件（上一张 / 下一张 + 指示点，默认显示）。 */
    public static final String CONTROLS_KEY = "features.page-banner.controls";
    /** 站点设置键：鼠标悬停是否暂停自动翻页（默认暂停）。 */
    public static final String PAUSE_ON_HOVER_KEY = "features.page-banner.pause-on-hover";

    /** 插件配置里的自动翻页间隔键（{@code wikikernel.plugins.settings.page-banner.interval}）。 */
    public static final String PLUGIN_INTERVAL_KEY = "interval";
    /** 插件配置里的过渡效果键。 */
    public static final String PLUGIN_TRANSITION_KEY = "transition";
    /** 插件配置里的控件开关键。 */
    public static final String PLUGIN_CONTROLS_KEY = "controls";
    /** 插件配置里的悬停暂停键。 */
    public static final String PLUGIN_PAUSE_ON_HOVER_KEY = "pause-on-hover";

    /** 自动翻页间隔的默认值（秒）。 */
    public static final int DEFAULT_INTERVAL = 5;
    /** 自动翻页间隔的上限（秒）：防止配置成几小时这种明显笔误。 */
    public static final int MAX_INTERVAL = 3600;

    /** 轮换机制的可用取值（顺序与 core 的 {@code PageBannerService.ROTATION_MODES} 一致）。 */
    public static final List<String> ROTATION_MODES = List.of("stable", "random", "rotate", "daily", "hourly");

    /** 过渡效果的可用取值（顺序即文档顺序）：淡入淡出 / 横向滑动。 */
    public static final List<String> TRANSITION_MODES = List.of("fade", "slide");

    /** 过渡效果的默认值。 */
    public static final String DEFAULT_TRANSITION = "fade";

    /** 轮播配置（间隔 / 过渡 / 控件 / 悬停暂停）。 */
    public record CarouselOptions(int intervalSeconds, String transition, boolean controls, boolean pauseOnHover) {
    }

    private final SiteConfig siteConfig;
    private final PluginConfig pluginConfig;

    public PageBannerSettings(SiteConfig siteConfig) {
        this(siteConfig, null);
    }

    /**
     * @param siteConfig   core 的站点设置（可空：单元测试 / 独立运行时全部走默认值）
     * @param pluginConfig 插件自己的配置（可空；{@code wikikernel.plugins.settings.page-banner.*}）
     */
    public PageBannerSettings(SiteConfig siteConfig, PluginConfig pluginConfig) {
        this.siteConfig = siteConfig;
        this.pluginConfig = pluginConfig;
    }

    /** 页面横幅是否启用（站点设置 {@code features.page-banner}，默认开启）。 */
    public boolean enabled() {
        return booleanSetting(new String[] {ENABLED_KEY}, true);
    }

    /** 横幅图片池（{@code |} 或换行分隔的文件页名称；顺序即轮播顺序）。 */
    public List<String> files() {
        return split(setting(FILES_KEY, ""));
    }

    /** 是否只对白名单页面显示横幅（默认开启）。 */
    public boolean whitelistEnabled() {
        return booleanSetting(new String[] {WHITELIST_ENABLED_KEY}, true);
    }

    /** 横幅白名单页面（{@code |} 或换行分隔的页面标题）。 */
    public List<String> whitelist() {
        return split(setting(WHITELIST_KEY, ""));
    }

    /** 轮换机制（非法值回退 {@code stable}）。 */
    public String rotation() {
        String mode = setting(ROTATION_KEY, "stable").trim().toLowerCase(Locale.ROOT);
        return ROTATION_MODES.contains(mode) ? mode : "stable";
    }

    /**
     * 逐页配置（页面标题 → 横幅文件名；多个文件名用 {@code |} 分隔 = 该页轮播的固定顺序，
     * 空字符串 = 交给轮换机制）。值原样保留（只按换行拆条目），拆分成多个文件名由渲染器负责。
     */
    public Map<String, String> perPage() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String item : splitByLines(setting(PER_PAGE_KEY, ""))) {
            int equals = item.indexOf('=');
            String title = equals >= 0 ? item.substring(0, equals) : item;
            String file = equals >= 0 ? item.substring(equals + 1) : "";
            String normalized = normalize(title);
            if (!normalized.isEmpty()) {
                mapping.put(normalized, file.trim());
            }
        }
        return mapping;
    }

    /** 自动翻页间隔（秒）：默认 {@value #DEFAULT_INTERVAL}，{@code 0} = 不自动翻页（负数 / 非法值按 0 处理）。 */
    public int interval() {
        return intervalSeconds(new String[] {INTERVAL_KEY}, new String[] {PLUGIN_INTERVAL_KEY});
    }

    /** 过渡效果（{@code fade} / {@code slide}，非法值回退 {@value #DEFAULT_TRANSITION}）。 */
    public String transition() {
        String mode = setting(TRANSITION_KEY, PLUGIN_TRANSITION_KEY, DEFAULT_TRANSITION)
                .trim().toLowerCase(Locale.ROOT);
        return TRANSITION_MODES.contains(mode) ? mode : DEFAULT_TRANSITION;
    }

    /** 是否显示翻页控件（上一张 / 下一张 + 指示点），默认 {@code true}。 */
    public boolean controls() {
        return booleanSetting(new String[] {CONTROLS_KEY}, new String[] {PLUGIN_CONTROLS_KEY}, true);
    }

    /** 鼠标悬停时是否暂停自动翻页，默认 {@code true}。 */
    public boolean pauseOnHover() {
        return booleanSetting(new String[] {PAUSE_ON_HOVER_KEY}, new String[] {PLUGIN_PAUSE_ON_HOVER_KEY}, true);
    }

    /** 一次取全轮播配置（渲染器 / 钩子用，避免反复读设置）。 */
    public CarouselOptions carousel() {
        return new CarouselOptions(interval(), transition(), controls(), pauseOnHover());
    }

    /**
     * 按轮换机制从图片池里选取一张（池为空时返回空串）。
     *
     * @param pageTitle   页面标题（stable / daily / hourly 用它做哈希种子）
     * @param rotateIndex {@code rotate} 模式的递增序号（只在真的用到时求值：进程内计数器，重启归零）
     */
    public String pick(String pageTitle, LongSupplier rotateIndex) {
        List<String> files = files();
        if (files.isEmpty()) {
            return "";
        }
        int seed = pageTitle == null ? 0 : pageTitle.hashCode();
        int index = switch (rotation()) {
            case "random" -> ThreadLocalRandom.current().nextInt(files.size());
            case "rotate" -> (int) Math.floorMod(rotateIndex.getAsLong(), files.size());
            case "daily" -> Math.floorMod(LocalDate.now().toEpochDay() + seed, files.size());
            case "hourly" -> Math.floorMod(LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                    .hashCode() + seed, files.size());
            default -> Math.floorMod(seed, files.size()); // stable
        };
        return files.get(index);
    }

    /** 取一条设置：插件配置（短键）优先，其次是站点设置（键与 core 一致）。 */
    private String setting(String key, String defaultValue) {
        String value = pluginConfig == null ? null : pluginConfig.get(key, null);
        if (value != null) {
            return value;
        }
        String fallback = siteConfig == null ? null : siteConfig.get(key, null);
        return fallback == null ? defaultValue : fallback;
    }

    /** 取一条设置：插件配置（短键）→ 站点设置 → 默认值。 */
    private String setting(String siteKey, String pluginKey, String defaultValue) {
        String value = pluginConfig == null ? null : pluginConfig.get(pluginKey, null);
        if (value != null) {
            return value;
        }
        String fallback = siteConfig == null ? null : siteConfig.get(siteKey, null);
        return fallback == null ? defaultValue : fallback;
    }

    /** 自动翻页间隔：插件配置（短键）→ 站点设置 → 默认值；负数按 {@code 0}（不自动翻页），过大的值截到上限。 */
    private int intervalSeconds(String[] siteKeys, String[] pluginKeys) {
        String value = firstNonNull(siteKeys, pluginKeys);
        if (value == null || value.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            int seconds = Integer.parseInt(value.trim());
            if (seconds < 0) {
                return 0;
            }
            return Math.min(seconds, MAX_INTERVAL);
        } catch (NumberFormatException exception) {
            return DEFAULT_INTERVAL;
        }
    }

    /** 布尔设置：插件配置（短键）→ 站点设置 → 默认值（只认**实际存在**的键）。 */
    private boolean booleanSetting(String[] siteKeys, boolean defaultValue) {
        return booleanSetting(siteKeys, new String[0], defaultValue);
    }

    private boolean booleanSetting(String[] siteKeys, String[] pluginKeys, boolean defaultValue) {
        if (pluginConfig != null) {
            for (String key : pluginKeys) {
                String value = pluginConfig.get(key, null);
                if (value != null && !value.isBlank()) {
                    return truthy(value);
                }
            }
        }
        if (siteConfig != null) {
            for (String key : siteKeys) {
                String value = siteConfig.get(key, null);
                if (value != null && !value.isBlank()) {
                    return truthy(value);
                }
            }
        }
        return defaultValue;
    }

    /** 站点设置 / 插件配置里第一个有值的键（插件配置短键优先，与 {@link #booleanSetting} 一致）。 */
    private String firstNonNull(String[] siteKeys, String[] pluginKeys) {
        if (pluginConfig != null) {
            for (String key : pluginKeys) {
                String value = pluginConfig.get(key, null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        if (siteConfig != null) {
            for (String key : siteKeys) {
                String value = siteConfig.get(key, null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** core 的站点设置归一化：true / 1 / on / yes 视为真（大小写不敏感）。 */
    private static boolean truthy(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    /**
     * 标题规范化（core {@code WikiTitles.normalize} 的常用子集：下划线 / 空白折叠成一个空格，
     * 命名空间后的首字母大写）。core 的规范实现不在插件可见范围内，这里只对齐横幅比对需要的部分。
     */
    public static String normalize(String title) {
        String value = fold(title);
        if (value.isEmpty()) {
            return "";
        }
        int colon = value.indexOf(':');
        int start = colon >= 0 && colon + 1 < value.length() ? colon + 1 : 0;
        return value.substring(0, start) + capitalizeFirst(value.substring(start));
    }

    /** 下划线 / 各种空白折叠成一个空格并去掉首尾空白（MediaWiki 的标题等价规则）。 */
    private static String fold(String title) {
        String source = title == null ? "" : title;
        StringBuilder out = new StringBuilder(source.length());
        boolean pendingSpace = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '_' || c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == '\u00A0' || c == '\u3000' || (c >= '\u2000' && c <= '\u200A')) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 首字符大写（只影响一个码点，其余原样）。 */
    private static String capitalizeFirst(String value) {
        if (value.isEmpty()) {
            return "";
        }
        int codePoint = value.codePointAt(0);
        int upper = Character.toUpperCase(codePoint);
        if (upper == codePoint) {
            return value;
        }
        return new StringBuilder(value.length()).appendCodePoint(upper)
                .append(value, Character.charCount(codePoint), value.length()).toString();
    }

    /** {@code |} 或换行分隔 → 列表（去空白、去空项、去重且保持顺序）。 */
    private static List<String> split(String value) {
        List<String> items = new ArrayList<>();
        for (String item : value.split("[|\\r\\n]+")) {
            String text = item.trim();
            if (!text.isEmpty() && !items.contains(text)) {
                items.add(text);
            }
        }
        return List.copyOf(items);
    }

    /** 只按换行拆条目（逐页配置用：{@code |} 在值里是「多个文件名」的分隔符，不能在这里拆）。 */
    private static List<String> splitByLines(String value) {
        List<String> items = new ArrayList<>();
        for (String item : value.split("[\\r\\n]+")) {
            String text = item.trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return List.copyOf(items);
    }
}
