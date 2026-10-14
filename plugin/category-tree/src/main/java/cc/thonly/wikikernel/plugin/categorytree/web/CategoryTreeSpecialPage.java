package cc.thonly.wikikernel.plugin.categorytree.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeOptions;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「特殊:分类树」/ {@code Special:CategoryTree}（移植 {@code CategoryTreePage}）：
 * 输入分类名后以树形结构浏览成员，并显示该分类的上级分类。
 * <p>
 * 目标分类有两种写法：{@code ?target=分类名} 或子页形式 {@code 特殊:分类树/分类名}
 * （core 会把子页参数注入为 {@code name} / {@code page}）。
 * 表单参数：{@code mode} / {@code namespaces} / {@code showcount}（原版只提供前两项，
 * 这里额外给出 showcount 复选，便于对照 {@code $wgCategoryTreeSpecialPageOptions} 的默认值）。
 */
public final class CategoryTreeSpecialPage implements SpecialPage {

    /** 支持的分类树模式（表单下拉用）。 */
    private static final List<String> FORM_MODES =
            List.of(CategoryTreeOptions.MODE_CATEGORIES, CategoryTreeOptions.MODE_PAGES,
                    CategoryTreeOptions.MODE_ALL);

    private final CategoryTreeRenderer renderer;
    private final CategoryTreeConfig config;
    private final CategoryTreeMessages messages;

    public CategoryTreeSpecialPage(CategoryTreeRenderer renderer, CategoryTreeConfig config,
                                   CategoryTreeMessages messages) {
        this.renderer = renderer;
        this.config = config == null ? CategoryTreeConfig.defaults() : config;
        this.messages = messages == null ? CategoryTreeMessages.system() : messages;
    }

    @Override
    public String name() {
        return "分类树";
    }

    @Override
    public String displayName() {
        return this.messages.text("categorytree");
    }

    @Override
    public List<String> aliases() {
        return List.of("CategoryTree");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/category-tree";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String target = firstNonBlank(
                parameters.get("target"), parameters.get("name"), parameters.get("page"));
        String namespaces = trimToEmpty(parameters.get("namespaces"));
        String hidePrefix = trimToEmpty(parameters.get("hideprefix"));
        boolean hasShowCount = parameters.containsKey("showcount");
        boolean showCount = hasShowCount
                ? CategoryTreeOptions.decodeBoolean(parameters.get("showcount"))
                : this.config.defaultShowCount();
        String mode = CategoryTreeOptions.decodeMode(
                trimToEmpty(parameters.get("mode")).isEmpty() ? null : parameters.get("mode"), this.config);

        // 表单里默认选中项：设置了命名空间过滤时按原版固定显示「所有页面」（mode 会被命名空间覆盖）
        String formMode = namespaces.isEmpty() ? normalizeFormMode(mode) : CategoryTreeOptions.MODE_ALL;

        model.put("intro", this.messages.text("categorytree-header"));
        model.put("legend", this.messages.text("categorytree-legend"));
        model.put("target", target);
        model.put("modeOptions", modeOptions(formMode));
        model.put("namespaces", namespaces);
        model.put("showcount", showCount);
        model.put("categoryLabel", this.messages.text("categorytree-category"));
        model.put("modeLabel", this.messages.text("categorytree-mode-label"));
        model.put("namespacesLabel", this.messages.text("categorytree-namespaces-label"));
        model.put("showCountLabel", this.messages.text("categorytree-showcount-label"));
        model.put("goLabel", this.messages.text("categorytree-go"));
        model.put("messagesJson", this.messages.json());

        if (target.isEmpty()) {
            return;
        }
        String title = CategoryTreeRenderer.makeCategoryTitle(target);
        if (title == null || !this.renderer.categoryExists(title)) {
            model.put("noticeHtml", this.renderer.notFoundNotice(target));
            return;
        }

        Map<String, Object> rawOptions = new LinkedHashMap<>();
        rawOptions.put("mode", mode);
        if (!namespaces.isEmpty()) {
            rawOptions.put("namespaces", namespaces);
        }
        if (!hidePrefix.isEmpty()) {
            rawOptions.put("hideprefix", hidePrefix);
        }
        rawOptions.put("showcount", showCount);
        CategoryTreeOptions options = CategoryTreeOptions.parse(rawOptions, this.config);

        String parents = this.renderer.renderParents(title, options);
        model.put("parentsHtml", this.messages.text("categorytree-parents") + "："
                + (parents.isEmpty() ? this.messages.text("categorytree-no-parent-categories") : parents));
        model.put("treeHtml", this.renderer.renderResultTree(title, options));
    }

    /** 表单下拉里的模式只可能是 categories / pages / all（parents 归到「所有页面」）。 */
    private static String normalizeFormMode(String mode) {
        return switch (mode) {
            case CategoryTreeOptions.MODE_CATEGORIES -> CategoryTreeOptions.MODE_CATEGORIES;
            case CategoryTreeOptions.MODE_PAGES -> CategoryTreeOptions.MODE_PAGES;
            default -> CategoryTreeOptions.MODE_ALL;
        };
    }

    private List<Map<String, Object>> modeOptions(String selected) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(CategoryTreeOptions.MODE_CATEGORIES, this.messages.text("categorytree-mode-categories"));
        labels.put(CategoryTreeOptions.MODE_PAGES, this.messages.text("categorytree-mode-pages"));
        labels.put(CategoryTreeOptions.MODE_ALL, this.messages.text("categorytree-mode-all"));
        return FORM_MODES.stream().map(mode -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("value", mode);
            option.put("label", labels.get(mode));
            option.put("selected", mode.equals(selected));
            return option;
        }).toList();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
