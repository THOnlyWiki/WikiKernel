package cc.thonly.wikikernel.plugin.categorytree.service;

import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 分类树的选项（移植 {@code OptionManager}）：解析 / 规范化 / 序列化为 JS 结构 / 深度限制。
 * <p>
 * 选项来源有四种，最终都归一到本类：
 * <ol>
 *     <li>{@code <categorytree>} 的 HTML 属性；</li>
 *     <li>{@code {{#categorytree:分类|mode=all}}} 的命名参数；</li>
 *     <li>{@code action=categorytree} 的 {@code options} JSON；</li>
 *     <li>「特殊:分类树」的表单参数。</li>
 * </ol>
 * 已知选项：{@code mode} / {@code hideprefix} / {@code showcount} / {@code namespaces} / {@code notranslations}，
 * 其余键（{@code depth} / {@code hideroot} / {@code class} / {@code style} / {@code id}）由调用方单独处理，与 MediaWiki 一致。
 */
public final class CategoryTreeOptions {

    /** 只显示子分类（原版旧数值 {@code 0}，别名 {@code sub}）。 */
    public static final String MODE_CATEGORIES = "categories";
    /** 除文件外的页面（原版旧数值 {@code 10}）。 */
    public static final String MODE_PAGES = "pages";
    /** 所有命名空间（原版旧数值 {@code 20}）。 */
    public static final String MODE_ALL = "all";
    /** 上级分类，即反向查询（原版旧数值 {@code 100}，别名 {@code super} / {@code inverse}）。 */
    public static final String MODE_PARENTS = "parents";

    /** 不隐藏命名空间前缀。 */
    public static final String HIDE_PREFIX_NEVER = "never";
    /** 总是隐藏命名空间前缀。 */
    public static final String HIDE_PREFIX_ALWAYS = "always";
    /** 只对分类命名空间的节点隐藏前缀（原版默认，别名 {@code category} / {@code smart}）。 */
    public static final String HIDE_PREFIX_CATEGORIES = "categories";
    /** 仅 mode=categories 时隐藏前缀。 */
    public static final String HIDE_PREFIX_AUTO = "auto";

    /** 原版 1.47 之前的 mode 数值写法。 */
    private static final Map<Long, String> LEGACY_MODES = Map.of(
            0L, MODE_CATEGORIES,
            10L, MODE_PAGES,
            20L, MODE_ALL,
            100L, MODE_PARENTS);

    /** 原版 1.47 之前的 hideprefix 数值写法。 */
    private static final Map<Long, String> LEGACY_HIDE_PREFIXES = Map.of(
            0L, HIDE_PREFIX_NEVER,
            10L, HIDE_PREFIX_ALWAYS,
            20L, HIDE_PREFIX_CATEGORIES,
            30L, HIDE_PREFIX_AUTO);

    /** 命名空间名称 → MediaWiki 风格编号（原版走 {@code Language::getNsIndex}）。 */
    private static final Map<String, Integer> NAMESPACE_NAMES = namespaceNames();

    /** 已知选项名（构造 {@code data-ct-options} 时按这个顺序输出，与原版一致）。 */
    private static final List<String> KNOWN_OPTIONS =
            List.of("mode", "hideprefix", "showcount", "namespaces", "notranslations");

    private final String mode;
    private final String hidePrefix;
    private final boolean showCount;
    private final List<Integer> namespaces;
    private final boolean noTranslations;
    private final CategoryTreeConfig config;

    private CategoryTreeOptions(String mode, String hidePrefix, boolean showCount,
                                List<Integer> namespaces, boolean noTranslations,
                                CategoryTreeConfig config) {
        this.mode = mode;
        this.hidePrefix = hidePrefix;
        this.showCount = showCount;
        this.namespaces = List.copyOf(namespaces);
        this.noTranslations = noTranslations;
        this.config = config;
    }

    /**
     * 解析选项（对应 {@code OptionManager::__construct}）：
     * 只认识已知选项，未知键忽略；先规范化 mode，parents 模式忽略 namespaces，
     * 再规范化 hideprefix / showcount / namespaces，最后按 namespaces 自动调整 mode。
     *
     * @param raw    原始选项（值可以是 String / Boolean / Number / List / null）
     * @param config 插件配置（提供默认值与深度上限）
     */
    public static CategoryTreeOptions parse(Map<String, ?> raw, CategoryTreeConfig config) {
        CategoryTreeConfig effective = config == null ? CategoryTreeConfig.defaults() : config;
        Map<String, Object> values = new LinkedHashMap<>();
        if (raw != null) {
            for (String option : KNOWN_OPTIONS) {
                values.put(option, raw.get(option));
            }
        }
        String mode = decodeMode(values.get("mode"), effective);
        if (MODE_PARENTS.equals(mode)) {
            // 反向模式下命名空间过滤没有意义
            values.put("namespaces", null);
        }
        String hidePrefix = decodeHidePrefix(values.get("hideprefix"), effective);
        boolean showCount = values.get("showcount") == null
                ? effective.defaultShowCount()
                : decodeBoolean(values.get("showcount"));
        List<Integer> namespaces = decodeNamespaces(values.get("namespaces"));
        if (!namespaces.isEmpty()) {
            // 按命名空间过滤器自动调整 mode
            if (namespaces.size() == 1 && namespaces.get(0) == 14) {
                mode = MODE_CATEGORIES;
            } else if (!namespaces.contains(6)) {
                mode = MODE_PAGES;
            } else {
                mode = MODE_ALL;
            }
        }
        return new CategoryTreeOptions(mode, hidePrefix, showCount, namespaces,
                decodeBoolean(values.get("notranslations")), effective);
    }

    /** 空选项（全部走配置默认值）。 */
    public static CategoryTreeOptions defaults(CategoryTreeConfig config) {
        return parse(Map.of(), config);
    }

    public String mode() {
        return this.mode;
    }

    public String hidePrefix() {
        return this.hidePrefix;
    }

    public boolean showCount() {
        return this.showCount;
    }

    /** 命名空间过滤器（空列表 = 未设置）。 */
    public List<Integer> namespaces() {
        return this.namespaces;
    }

    public boolean noTranslations() {
        return this.noTranslations;
    }

    /** 是否反向模式（mode=parents）。 */
    public boolean isInverse() {
        return MODE_PARENTS.equals(this.mode);
    }

    public CategoryTreeConfig config() {
        return this.config;
    }

    /** 按 mode 取深度上限并截断（对应 {@code OptionManager::capDepth}）。 */
    public int capDepth(int depth) {
        int max = switch (this.mode) {
            case MODE_CATEGORIES -> this.config.maxDepthCategories();
            case MODE_PAGES -> this.config.maxDepthPages();
            default -> this.config.maxDepthAll();
        };
        return Math.min(depth, max);
    }

    /** 归一化后的选项（用于 {@code data-ct-options} 与缓存键）：键顺序与原版一致。 */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", this.mode);
        map.put("hideprefix", this.hidePrefix);
        map.put("showcount", this.showCount);
        // 未设置命名空间时原版编码为 false（不是空数组）
        map.put("namespaces", this.namespaces.isEmpty() ? Boolean.FALSE : List.copyOf(this.namespaces));
        map.put("notranslations", this.noTranslations);
        return map;
    }

    /** {@code data-ct-options} / {@code options} 参数用的 JSON 字符串。 */
    public String asJson() {
        return CategoryTreeJson.stringify(asMap());
    }

    /**
     * 缓存键（对应 {@code OptionManager::getOptionsAsCacheKey}）：{@code mode:categories;…;depth=1}。
     */
    public String cacheKey(Integer depth) {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, Object> entry : asMap().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                value = String.join("|", list.stream().map(String::valueOf).toList());
            }
            key.append(entry.getKey()).append(':').append(value).append(';');
        }
        if (depth != null) {
            key.append(";depth=").append(depth);
        }
        return key.toString();
    }

    // ==================== 值规范化（对齐 PHP 的松散比较） ====================

    /**
     * 布尔值：{@code null}/{@code false} → false；数字 → 截断后 &gt; 0；
     * 字符串 {@code yes/y/true/t/on} → true（对应 {@code OptionManager::decodeBoolean}）。
     */
    public static boolean decodeBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return (long) number.doubleValue() > 0;
        }
        if (value instanceof List<?>) {
            // PHP 里非空数组转布尔为 true
            return !((List<?>) value).isEmpty();
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (isNumeric(text)) {
            return (long) Double.parseDouble(text) > 0;
        }
        return switch (text) {
            case "yes", "y", "true", "t", "on" -> true;
            default -> false;
        };
    }

    /**
     * mode 规范化（对应 {@code OptionManager::decodeMode}）：
     * {@code null} / {@code default} / 无法识别的空值都回退到配置的 {@code default-mode}，
     * 配置默认值本身也可能是旧数值 / 别名。
     */
    public static String decodeMode(Object value, CategoryTreeConfig config) {
        CategoryTreeConfig effective = config == null ? CategoryTreeConfig.defaults() : config;
        if (value != null) {
            String decoded = modeValue(value);
            if (decoded != null) {
                return decoded;
            }
        }
        String fallback = effective.defaultMode();
        String decoded = fallback == null ? null : modeValue(fallback);
        return decoded == null ? MODE_CATEGORIES : decoded;
    }

    /** 单个 mode 值 → 常量；{@code null} 表示「用默认值」（对应 {@code default} 写法）。 */
    private static String modeValue(Object value) {
        if (value instanceof Number number) {
            return LEGACY_MODES.getOrDefault((long) number.doubleValue(), MODE_CATEGORIES);
        }
        if (!(value instanceof String text)) {
            return MODE_CATEGORIES;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return MODE_CATEGORIES;
        }
        if (isNumeric(normalized)) {
            return LEGACY_MODES.getOrDefault((long) Double.parseDouble(normalized), MODE_CATEGORIES);
        }
        return switch (normalized) {
            case "all" -> MODE_ALL;
            case "pages" -> MODE_PAGES;
            case "categories", "sub" -> MODE_CATEGORIES;
            case "parents", "super", "inverse" -> MODE_PARENTS;
            case "default" -> null;
            default -> MODE_CATEGORIES;
        };
    }

    /**
     * hideprefix 规范化（对应 {@code OptionManager::decodeHidePrefix}）：
     * 支持 never/always/categories/auto 与别名 category/smart、布尔值、旧数值 0/10/20/30；
     * 非法的值回退到配置默认值，默认值本身也非法时用 never。
     */
    public static String decodeHidePrefix(Object value, CategoryTreeConfig config) {
        CategoryTreeConfig effective = config == null ? CategoryTreeConfig.defaults() : config;
        String fallback = hidePrefixValue(effective.defaultHidePrefix());
        if (fallback == null) {
            fallback = HIDE_PREFIX_NEVER;
        }
        if (value == null) {
            return fallback;
        }
        String decoded = hidePrefixValue(value);
        if (decoded != null) {
            return decoded;
        }
        // 与配置默认值写成同一个非法值时，原版直接回退 never（避免无限递归）
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals(effective.defaultHidePrefix() == null
                ? "" : effective.defaultHidePrefix().trim().toLowerCase(Locale.ROOT))) {
            return HIDE_PREFIX_NEVER;
        }
        return fallback;
    }

    /** 单个 hideprefix 值的解析；非法返回 {@code null}。 */
    private static String hidePrefixValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool ? HIDE_PREFIX_ALWAYS : HIDE_PREFIX_NEVER;
        }
        if (value instanceof Number number) {
            return LEGACY_HIDE_PREFIXES.getOrDefault((long) number.doubleValue(), HIDE_PREFIX_NEVER);
        }
        if (!(value instanceof String stringValue)) {
            return HIDE_PREFIX_NEVER;
        }
        String text = stringValue.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        // 布尔写法优先于数字写法：0/10/20/30 是旧数值，1 / 0 当布尔（0 两种写法结果一致）
        String bool = switch (text) {
            case "yes", "y", "true", "t", "on", "1" -> HIDE_PREFIX_ALWAYS;
            case "no", "n", "false", "f", "off", "0" -> HIDE_PREFIX_NEVER;
            default -> null;
        };
        if (bool != null) {
            return bool;
        }
        if (isNumeric(text)) {
            return LEGACY_HIDE_PREFIXES.getOrDefault((long) Double.parseDouble(text), HIDE_PREFIX_NEVER);
        }
        return switch (text) {
            case "always" -> HIDE_PREFIX_ALWAYS;
            case "never" -> HIDE_PREFIX_NEVER;
            case "auto" -> HIDE_PREFIX_AUTO;
            case "categories", "category", "smart" -> HIDE_PREFIX_CATEGORIES;
            default -> null;
        };
    }

    /**
     * namespaces 规范化（对应 {@code OptionManager::decodeNamespaces}）：
     * 数字编号，或 {@code -} / {@code _} / {@code *} / {@code main}（= 主命名空间 0），
     * 名称按 {@link #NAMESPACE_NAMES} 识别；分隔符可以是空格 / {@code #} / {@code :} / {@code |}。
     * 结果按编号升序去重；空结果表示「未设置」。
     */
    public static List<Integer> decodeNamespaces(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                parts.add(String.valueOf(item));
            }
        } else {
            String text = String.valueOf(value);
            if (text.isBlank()) {
                return List.of();
            }
            for (String part : text.split("[\\s#:|]+")) {
                parts.add(part);
            }
        }
        TreeSet<Integer> ids = new TreeSet<>();
        for (String part : parts) {
            Integer id = namespaceId(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** 单个命名空间写法 → 编号；无法识别返回 {@code null}。 */
    private static Integer namespaceId(String part) {
        if (part == null) {
            return null;
        }
        String text = part.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isNumeric(text)) {
            return (int) Double.parseDouble(text);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ("-".equals(text) || "_".equals(text) || "*".equals(text) || "main".equals(lower)) {
            return 0;
        }
        return NAMESPACE_NAMES.get(lower.replace('_', ' ').trim());
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        double ignored;
        try {
            ignored = Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return false;
        }
        return !Double.isNaN(ignored);
    }

    /** 常用命名空间的名称（中英文）→ MediaWiki 编号，与 core 的命名空间编号一致。 */
    private static Map<String, Integer> namespaceNames() {
        Map<String, Integer> names = new LinkedHashMap<>();
        names.put("main", 0);
        names.put("article", 0);
        names.put("主", 0);
        names.put("主命名空间", 0);
        names.put("条目", 0);
        names.put("talk", 1);
        names.put("讨论", 1);
        names.put("user", 2);
        names.put("用户", 2);
        names.put("user talk", 3);
        names.put("用户讨论", 3);
        names.put("wikikernel", 4);
        names.put("wikikernel talk", 5);
        names.put("file", 6);
        names.put("image", 6);
        names.put("文件", 6);
        names.put("图像", 6);
        names.put("file talk", 7);
        names.put("文件讨论", 7);
        names.put("template", 10);
        names.put("模板", 10);
        names.put("template talk", 11);
        names.put("模板讨论", 11);
        names.put("help", 12);
        names.put("帮助", 12);
        names.put("help talk", 13);
        names.put("帮助讨论", 13);
        names.put("category", 14);
        names.put("分类", 14);
        names.put("category talk", 15);
        names.put("分类讨论", 15);
        names.put("widget", 274);
        names.put("微件", 274);
        names.put("graph", 484);
        names.put("图表", 484);
        names.put("module", 828);
        names.put("模块", 828);
        names.put("gadget", 2300);
        names.put("小工具", 2300);
        return Map.copyOf(names);
    }
}
