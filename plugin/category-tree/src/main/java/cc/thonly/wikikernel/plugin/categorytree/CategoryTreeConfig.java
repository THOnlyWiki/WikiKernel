package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeOptions;

/**
 * CategoryTree 插件配置（对应 MediaWiki 的 {@code $wgCategoryTree*} 系列），
 * 写在 {@code LocalSettings.yaml} 的：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       category-tree:
 *         allow-tag: true            # false = 不注册 &lt;categorytree&gt; / {{#categorytree}}（对应 $wgCategoryTreeAllowTag）
 *         max-children: 200          # 每个节点最多渲染多少个直接成员（对应 $wgCategoryTreeMaxChildren）
 *         max-depth-categories: 2    # mode=categories 的最大展开深度（对应 $wgCategoryTreeMaxDepth.categories）
 *         max-depth-pages: 1         # mode=pages 的最大展开深度（对应 $wgCategoryTreeMaxDepth.pages）
 *         max-depth-all: 1           # mode=all 的最大展开深度（对应 $wgCategoryTreeMaxDepth.all）
 *         default-mode: categories   # 默认 mode（对应 $wgCategoryTreeDefaultMode）
 *         default-hideprefix: categories # 默认 hideprefix（对应 $wgCategoryTreeOmitNamespace）
 *         default-showcount: false   # 默认 showcount（原版为固定 false）
 * </pre>
 *
 * @param allowTag          是否注册解析入口（标签 + 解析器函数）
 * @param maxChildren       每个节点最多渲染多少个直接成员
 * @param maxDepthCategories mode=categories 的最大展开深度
 * @param maxDepthPages     mode=pages 的最大展开深度
 * @param maxDepthAll       mode=all / parents 的最大展开深度
 * @param defaultMode       默认 mode（categories / pages / all / parents）
 * @param defaultHidePrefix 默认 hideprefix（never / always / categories / auto）
 * @param defaultShowCount  默认 showcount
 */
public record CategoryTreeConfig(boolean allowTag,
                                 int maxChildren,
                                 int maxDepthCategories,
                                 int maxDepthPages,
                                 int maxDepthAll,
                                 String defaultMode,
                                 String defaultHidePrefix,
                                 boolean defaultShowCount) {

    /** 插件默认配置（与 MediaWiki 的扩展默认值一致，showcount 取 false）。 */
    public static CategoryTreeConfig defaults() {
        return new CategoryTreeConfig(true, 200, 2, 1, 1,
                CategoryTreeOptions.MODE_CATEGORIES, CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, false);
    }

    /** 从插件配置读取（缺省 / 非法值回退到默认）。 */
    public static CategoryTreeConfig from(PluginConfig config) {
        CategoryTreeConfig defaults = defaults();
        if (config == null) {
            return defaults;
        }
        return new CategoryTreeConfig(
                config.getBoolean("allow-tag", defaults.allowTag()),
                positive(config.getInt("max-children", defaults.maxChildren()), defaults.maxChildren()),
                nonNegative(config.getInt("max-depth-categories", defaults.maxDepthCategories()),
                        defaults.maxDepthCategories()),
                nonNegative(config.getInt("max-depth-pages", defaults.maxDepthPages()),
                        defaults.maxDepthPages()),
                nonNegative(config.getInt("max-depth-all", defaults.maxDepthAll()),
                        defaults.maxDepthAll()),
                text(config.get("default-mode"), defaults.defaultMode()),
                text(config.get("default-hideprefix"), defaults.defaultHidePrefix()),
                config.getBoolean("default-showcount", defaults.defaultShowCount()));
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int nonNegative(int value, int fallback) {
        return value >= 0 ? value : fallback;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
