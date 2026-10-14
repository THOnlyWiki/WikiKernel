package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeOptions;
import java.util.List;
import java.util.Map;

/**
 * CategoryTree 的安装向导入口（写入 {@code wikikernel.plugins.settings.category-tree.*}）。
 * 字段与 {@link CategoryTreeConfig} 一一对应；文案走插件消息包里的 {@code setup.category-tree.*} 键。
 */
public class CategoryTreeSetup implements SetupStep {

    private final CategoryTreeConfig current;
    private final CategoryTreeMessages messages;

    public CategoryTreeSetup(CategoryTreeConfig current, CategoryTreeMessages messages) {
        this.current = current;
        this.messages = messages == null ? CategoryTreeMessages.system() : messages;
    }

    @Override
    public String id() {
        return "category-tree";
    }

    @Override
    public String displayName() {
        return "CategoryTree";
    }

    @Override
    public String description() {
        return text("setup.category-tree.description");
    }

    @Override
    public int order() {
        return 130;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.checkbox("allow-tag", text("setup.category-tree.allow-tag"), this.current.allowTag()),
                SetupField.text("max-children", text("setup.category-tree.max-children"),
                        String.valueOf(this.current.maxChildren())),
                SetupField.text("max-depth-categories", text("setup.category-tree.max-depth-categories"),
                        String.valueOf(this.current.maxDepthCategories())),
                SetupField.text("max-depth-pages", text("setup.category-tree.max-depth-pages"),
                        String.valueOf(this.current.maxDepthPages())),
                SetupField.text("max-depth-all", text("setup.category-tree.max-depth-all"),
                        String.valueOf(this.current.maxDepthAll())),
                SetupField.select("default-mode", text("setup.category-tree.default-mode"), this.current.defaultMode(),
                        List.of(CategoryTreeOptions.MODE_CATEGORIES, CategoryTreeOptions.MODE_PAGES,
                                CategoryTreeOptions.MODE_ALL, CategoryTreeOptions.MODE_PARENTS)),
                SetupField.select("default-hideprefix", text("setup.category-tree.default-hideprefix"),
                        this.current.defaultHidePrefix(),
                        List.of(CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, CategoryTreeOptions.HIDE_PREFIX_NEVER,
                                CategoryTreeOptions.HIDE_PREFIX_ALWAYS, CategoryTreeOptions.HIDE_PREFIX_AUTO)),
                SetupField.checkbox("default-showcount", text("setup.category-tree.default-showcount"),
                        this.current.defaultShowCount()));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        CategoryTreeConfig defaults = CategoryTreeConfig.defaults();
        context.putPluginSetting("allow-tag", checkbox(values, "allow-tag", defaults.allowTag()));
        putInt(context, values, "max-children", defaults.maxChildren(), 1);
        putInt(context, values, "max-depth-categories", defaults.maxDepthCategories(), 0);
        putInt(context, values, "max-depth-pages", defaults.maxDepthPages(), 0);
        putInt(context, values, "max-depth-all", defaults.maxDepthAll(), 0);
        context.putPluginSetting("default-mode",
                text(values, "default-mode", defaults.defaultMode()));
        context.putPluginSetting("default-hideprefix",
                text(values, "default-hideprefix", defaults.defaultHidePrefix()));
        context.putPluginSetting("default-showcount", checkbox(values, "default-showcount", defaults.defaultShowCount()));
        return SetupResult.ok(text("setup.category-tree.saved"));
    }

    /** 取一条本地化文案（插件消息包里的键；缺失时显示键名本身）。 */
    private String text(String key) {
        return this.messages.text(key);
    }

    private static void putInt(SetupContext context, Map<String, String> values, String key,
                               int fallback, int min) {
        int value = fallback;
        String raw = values.get(key);
        if (raw != null && !raw.isBlank()) {
            try {
                value = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        context.putPluginSetting(key, String.valueOf(Math.max(min, value)));
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String raw = values.get(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static String checkbox(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? Boolean.toString(fallback) : Boolean.toString("true".equalsIgnoreCase(value));
    }
}
