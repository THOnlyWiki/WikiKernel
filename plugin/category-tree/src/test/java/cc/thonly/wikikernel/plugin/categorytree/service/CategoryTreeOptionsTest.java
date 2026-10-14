package cc.thonly.wikikernel.plugin.categorytree.service;

import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项解析（移植 {@code OptionManager}）：mode / hideprefix / namespaces / 旧数值 / 布尔 / capDepth / JSON。
 */
class CategoryTreeOptionsTest {

    private static final CategoryTreeConfig CONFIG = CategoryTreeConfig.defaults();

    private static CategoryTreeOptions parse(Map<String, ?> options) {
        return CategoryTreeOptions.parse(options, CONFIG);
    }

    @Test
    void defaultsComeFromConfig() {
        CategoryTreeOptions options = parse(Map.of());
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, options.mode());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, options.hidePrefix());
        assertFalse(options.showCount());
        assertTrue(options.namespaces().isEmpty());
        assertFalse(options.noTranslations());
        assertFalse(options.isInverse());
        assertEquals("{\"mode\":\"categories\",\"hideprefix\":\"categories\",\"showcount\":false,"
                + "\"namespaces\":false,\"notranslations\":false}", options.asJson());
    }

    @Test
    void configProvidesAlternativeDefaults() {
        CategoryTreeConfig config = new CategoryTreeConfig(true, 50, 3, 2, 1,
                "all", "never", true);
        CategoryTreeOptions options = CategoryTreeOptions.parse(Map.of(), config);
        assertEquals(CategoryTreeOptions.MODE_ALL, options.mode());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_NEVER, options.hidePrefix());
        assertTrue(options.showCount());
    }

    @Test
    void modeAliasesAndLegacyNumbers() {
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", "sub")).mode());
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", "categories")).mode());
        assertEquals(CategoryTreeOptions.MODE_PAGES, parse(map("mode", "pages")).mode());
        assertEquals(CategoryTreeOptions.MODE_ALL, parse(map("mode", "all")).mode());
        assertEquals(CategoryTreeOptions.MODE_PARENTS, parse(map("mode", "parents")).mode());
        assertEquals(CategoryTreeOptions.MODE_PARENTS, parse(map("mode", "super")).mode());
        assertEquals(CategoryTreeOptions.MODE_PARENTS, parse(map("mode", "inverse")).mode());
        // 旧数值写法（1.47 之前）
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", "0")).mode());
        assertEquals(CategoryTreeOptions.MODE_PAGES, parse(map("mode", "10")).mode());
        assertEquals(CategoryTreeOptions.MODE_ALL, parse(map("mode", "20")).mode());
        assertEquals(CategoryTreeOptions.MODE_PARENTS, parse(map("mode", "100")).mode());
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", 100500L)).mode());
        // default / 未知值
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", "default")).mode());
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, parse(map("mode", "不相干的模式")).mode());
    }

    @Test
    void hidePrefixValuesAndLegacyNumbers() {
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_ALWAYS, parse(map("hideprefix", "always")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_NEVER, parse(map("hideprefix", "never")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_AUTO, parse(map("hideprefix", "auto")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, parse(map("hideprefix", "categories")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, parse(map("hideprefix", "category")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, parse(map("hideprefix", "smart")).hidePrefix());
        // 布尔写法
        for (String value : List.of("yes", "true", "on", "1")) {
            assertEquals(CategoryTreeOptions.HIDE_PREFIX_ALWAYS, parse(map("hideprefix", value)).hidePrefix(), value);
        }
        for (String value : List.of("no", "false", "off", "0")) {
            assertEquals(CategoryTreeOptions.HIDE_PREFIX_NEVER, parse(map("hideprefix", value)).hidePrefix(), value);
        }
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_ALWAYS, parse(map("hideprefix", true)).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_NEVER, parse(map("hideprefix", false)).hidePrefix());
        // 旧数值
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_NEVER, parse(map("hideprefix", "0")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_ALWAYS, parse(map("hideprefix", "10")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, parse(map("hideprefix", "20")).hidePrefix());
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_AUTO, parse(map("hideprefix", "30")).hidePrefix());
        // 非法值回退到配置默认值
        assertEquals(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, parse(map("hideprefix", "???")).hidePrefix());
    }

    @Test
    void namespacesDecodingAndAutomaticMode() {
        // 只有分类命名空间 → mode=categories
        CategoryTreeOptions categories = parse(map("namespaces", "14"));
        assertEquals(List.of(14), categories.namespaces());
        assertEquals(CategoryTreeOptions.MODE_CATEGORIES, categories.mode());
        // 不含文件命名空间 → mode=pages
        CategoryTreeOptions pages = parse(map("namespaces", "0|14"));
        assertEquals(List.of(0, 14), pages.namespaces());
        assertEquals(CategoryTreeOptions.MODE_PAGES, pages.mode());
        // 含文件命名空间 → mode=all
        CategoryTreeOptions all = parse(map("namespaces", "6"));
        assertEquals(List.of(6), all.namespaces());
        assertEquals(CategoryTreeOptions.MODE_ALL, all.mode());
        // 各种分隔符与主命名空间写法
        assertEquals(List.of(0), parse(map("namespaces", "-")).namespaces());
        assertEquals(List.of(0), parse(map("namespaces", "_")).namespaces());
        assertEquals(List.of(0), parse(map("namespaces", "*")).namespaces());
        assertEquals(List.of(0), parse(map("namespaces", "main")).namespaces());
        assertEquals(List.of(0, 6, 14), parse(map("namespaces", "14 6:0#")).namespaces());
        assertEquals(List.of(6, 14), parse(map("namespaces", "文件|分类")).namespaces());
        // 未设置 / 空值 → false
        assertTrue(parse(map("namespaces", "")).namespaces().isEmpty());
        assertTrue(parse(map("namespaces", false)).namespaces().isEmpty());
        assertTrue(parse(Map.of("namespaces", List.of())).namespaces().isEmpty());
        // 数组（API 传入 JSON 数组）
        assertEquals(List.of(0, 14), parse(Map.of("namespaces", List.of(0L, 14L))).namespaces());
    }

    @Test
    void parentsModeIgnoresNamespaces() {
        CategoryTreeOptions options = parse(map("mode", "parents", "namespaces", "0|14"));
        assertEquals(CategoryTreeOptions.MODE_PARENTS, options.mode());
        assertTrue(options.namespaces().isEmpty());
    }

    @Test
    void showCountAndNotTranslationsAcceptBooleans() {
        assertTrue(parse(map("showcount", "1")).showCount());
        assertTrue(parse(map("showcount", true)).showCount());
        assertFalse(parse(map("showcount", "0")).showCount());
        assertTrue(parse(map("notranslations", "yes")).noTranslations());
        assertFalse(parse(map("notranslations", "off")).noTranslations());
    }

    @Test
    void capDepthUsesModeSpecificLimits() {
        assertEquals(2, parse(map("mode", "categories")).capDepth(5));
        assertEquals(1, parse(map("mode", "pages")).capDepth(5));
        assertEquals(1, parse(map("mode", "all")).capDepth(5));
        assertEquals(1, parse(map("mode", "parents")).capDepth(5));
        assertEquals(1, parse(map("mode", "categories")).capDepth(1));
        assertEquals(0, parse(map("mode", "categories")).capDepth(0));
    }

    @Test
    void cacheKeyFollowsOriginalFormat() {
        CategoryTreeOptions options = parse(map("mode", "pages", "namespaces", "0|14"));
        // 原版 getOptionsAsCacheKey()：每个选项 k:v; 拼接，再追加 ;depth=N（因此 depth 前是双分号）
        assertEquals("mode:pages;hideprefix:categories;showcount:false;namespaces:0|14;"
                + "notranslations:false;;depth=1", options.cacheKey(1));
    }

    @Test
    void jsStructureKeepsOriginalKeyOrder() {
        CategoryTreeOptions options = parse(map("mode", "all", "showcount", "1"));
        Map<String, Object> map = options.asMap();
        assertEquals(List.of("mode", "hideprefix", "showcount", "namespaces", "notranslations"),
                List.copyOf(map.keySet()));
        assertEquals(Boolean.TRUE, map.get("showcount"));
        assertEquals(Boolean.FALSE, map.get("namespaces"));
        assertEquals("{\"mode\":\"all\",\"hideprefix\":\"categories\",\"showcount\":true,"
                + "\"namespaces\":false,\"notranslations\":false}", options.asJson());
    }

    @Test
    void configReadsKebabCaseSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("allow-tag", "false");
        settings.put("max-children", "50");
        settings.put("max-depth-categories", "3");
        settings.put("max-depth-pages", "2");
        settings.put("max-depth-all", "0");
        settings.put("default-mode", "pages");
        settings.put("default-hideprefix", "never");
        settings.put("default-showcount", "true");
        CategoryTreeConfig config = CategoryTreeConfig.from(new MapPluginConfig(settings));
        assertFalse(config.allowTag());
        assertEquals(50, config.maxChildren());
        assertEquals(3, config.maxDepthCategories());
        assertEquals(2, config.maxDepthPages());
        assertEquals(0, config.maxDepthAll());
        assertEquals("pages", config.defaultMode());
        assertEquals("never", config.defaultHidePrefix());
        assertTrue(config.defaultShowCount());
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(key, value);
        return options;
    }

    private static Map<String, Object> map(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> options = map(key1, value1);
        options.put(key2, value2);
        return options;
    }
}
