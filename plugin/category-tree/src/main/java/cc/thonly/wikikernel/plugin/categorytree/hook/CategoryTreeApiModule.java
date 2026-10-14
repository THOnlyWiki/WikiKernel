package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.ActionApiException;
import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.ActionApiRequest;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeJson;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeOptions;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code action=categorytree}（移植 {@code ApiCategoryTree}）：给前端 AJAX 展开节点用。
 * <p>
 * 参数：
 * <ul>
 *     <li>{@code category}（必填）：分类名，可带 / 不带「分类:」前缀；</li>
 *     <li>{@code options}（可选）：{@code data-ct-options} 那段 JSON 原样回传，{@code depth} 默认 1。</li>
 * </ul>
 * 输出 {@code {"categorytree":{"html":"…"}}}；{@code html} 是该分类的子节点渲染结果。
 * 选项不是 JSON 对象 → 错误码 {@code invalidjson}；选项值含数组（只允许 {@code namespaces}）
 * → 错误码 {@code invalidjson-option}（与原版一致）。
 * <p>
 * 与原版的差异：不实现 WAN 对象缓存的 {@code categorytree-html-ajax} 缓存与
 * {@code last-modified} 条件请求（core 未向插件暴露缓存 API）；每次请求都重新渲染。
 */
public final class CategoryTreeApiModule implements ActionApiModule {

    /** action 名称。 */
    public static final String ACTION_NAME = "categorytree";

    private final CategoryTreeRenderer renderer;
    private final CategoryTreeConfig config;

    public CategoryTreeApiModule(CategoryTreeRenderer renderer, CategoryTreeConfig config) {
        this.renderer = renderer;
        this.config = config == null ? CategoryTreeConfig.defaults() : config;
    }

    @Override
    public String action() {
        return ACTION_NAME;
    }

    @Override
    public Object handle(ActionApiRequest request) {
        CategoryTreeMessages messages = this.renderer.messages();
        String category = request.param("category").trim();
        if (category.isEmpty()) {
            throw new ActionApiException("missingparam",
                    messages.text("apierror-categorytree-missingcategory"));
        }
        Map<String, Object> rawOptions = parseOptions(request.param("options"), messages);
        CategoryTreeOptions options = CategoryTreeOptions.parse(rawOptions, this.config);
        String title = CategoryTreeRenderer.makeCategoryTitle(category);
        if (title == null) {
            throw new ActionApiException("invalidtitle",
                    messages.text("apierror-categorytree-invalidtitle", category));
        }
        int depth = options.capDepth(rawOptions.containsKey("depth") ? toInt(rawOptions.get("depth")) : 1);

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("html", this.renderer.renderChildren(title, depth, options));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ACTION_NAME, tree);
        return result;
    }

    /**
     * 解析 {@code options}：必须是 JSON 对象；值为标量 / {@code null}，
     * 数组只允许出现在 {@code namespaces} 上（对应 {@code ApiCategoryTree::extractOptions}）。
     */
    private static Map<String, Object> parseOptions(String json, CategoryTreeMessages messages) {
        String text = json == null ? "" : json.trim();
        if (text.isEmpty()) {
            return Map.of();
        }
        Object parsed = CategoryTreeJson.parseOrNull(text);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ActionApiException("invalidjson",
                    messages.text("apierror-categorytree-invalidjson"));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean) {
                options.put(key, value);
                continue;
            }
            if ("namespaces".equals(key) && value instanceof List<?>) {
                options.put(key, value);
                continue;
            }
            throw new ActionApiException("invalidjson-option",
                    messages.text("apierror-categorytree-invalidjson-option", key));
        }
        return options;
    }

    /** {@code depth} 的 PHP 风格整数转换：非数字 → 0（原版 {@code (int)$options['depth']}）。 */
    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return 0;
    }
}
