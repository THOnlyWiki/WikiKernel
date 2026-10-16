package cc.thonly.wikikernel.plugin.categorytree.service;

import cc.thonly.wikikernel.common.service.CategoryCounts;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.common.service.CategoryMember;
import cc.thonly.wikikernel.common.service.CategoryStore;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeNestedTag;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeTag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分类树渲染器（移植 {@code CategoryTree.php} 的 getTag / renderNode / renderChildren /
 * renderParents / renderNodeInfo / createCountString / makeTitle）。
 * <p>
 * 输出的 HTML 结构必须与前端 {@code ext.categoryTree.js} / {@code category-tree.css} 契约一致：
 * <pre>
 * &lt;div class="[用户 class ]CategoryTreeTag" [style] [id] data-ct-options='{…}'&gt;
 *   &lt;div class="CategoryTreeSection"&gt;
 *     &lt;div class="CategoryTreeItem"&gt;
 *       &lt;span class="CategoryTreeBullet"&gt;&lt;a class="CategoryTreeToggle" data-ct-title="X"
 *             aria-expanded="true|false" [data-ct-loaded] role="button" tabindex="0"&gt;&lt;/a&gt; &lt;/span&gt;
 *       &lt;bdi dir="ltr"&gt;&lt;a href="/wiki/分类:X"&gt;X&lt;/a&gt;&lt;/bdi&gt;
 *       [&lt;span title="…"&gt;(N个分类)&lt;/span&gt;]
 *     &lt;/div&gt;
 *     &lt;div class="CategoryTreeChildren" [style="display:none"]&gt;子节点…&lt;/div&gt;
 *   &lt;/div&gt;
 * &lt;/div&gt;
 * </pre>
 * 注意：{@code role} / {@code tabindex} / {@code title} 由前端 JS 在 DOMContentLoaded 时补上（与原版一致），
 * 服务端只输出上面这些属性。
 */
public final class CategoryTreeRenderer {

    /** 渲染上下文属性名：页面用到分类树时置 {@code 1}，{@code HtmlHeadProvider} 据此插入 JS / CSS。 */
    public static final String USED_PROPERTY = "categorytree-used";

    /** 分类命名空间编号（对应 MediaWiki 的 {@code NS_CATEGORY}）。 */
    public static final int NS_CATEGORY = 14;

    /** 文件命名空间编号（对应 MediaWiki 的 {@code NS_FILE}）。 */
    public static final int NS_FILE = 6;

    /** 分类命名空间前缀（core 的规范标题形如 {@code 分类:X}）。 */
    public static final String CATEGORY_PREFIX = "分类:";

    /** 特殊页路径（分类树的上级分类链接指向它）。 */


    /** 页面路径前缀。 */
    /** 页面路径前缀（按站点 URL 配置动态生成） */
    public static String specialPagePath() {
        return WikiUrls.pagePrefix() + "特殊:分类树";
    }

    /** 内容语言方向：WikiKernel 的内容语言是中文 / 英文，都是 ltr。 */
    private static final String DIR = "ltr";

    /** 根节点样式（原版 {@code CategoryTreeTag}）。 */
    private static final String TAG_CLASS = "CategoryTreeTag";

    private final CategoryStore categoryStore;
    private final WikiPageStore pageStore;
    private final CategoryTreeConfig config;
    private final CategoryTreeMessages messages;

    public CategoryTreeRenderer(CategoryStore categoryStore, WikiPageStore pageStore,
                                CategoryTreeConfig config, CategoryTreeMessages messages) {
        this.categoryStore = categoryStore;
        this.pageStore = pageStore;
        this.config = config == null ? CategoryTreeConfig.defaults() : config;
        this.messages = messages == null ? CategoryTreeMessages.system() : messages;
    }

    public CategoryTreeConfig config() {
        return this.config;
    }

    public CategoryTreeMessages messages() {
        return this.messages;
    }

    // ==================== 解析入口 ====================

    /**
     * 标签入口（对应 {@code CategoryTree::getTag}）：属性即选项，参数是分类名。
     *
     * @param attributes 标签属性 / 解析器函数的命名参数
     * @param categoryInput 分类名（可带 / 不带「分类:」前缀，可带 {@code _}）
     * @return 根 {@code <div>}；分类名为空时返回空串
     */
    public String renderTag(Map<String, String> attributes, String categoryInput) {
        String category = categoryInput == null ? "" : categoryInput.trim();
        String title = makeCategoryTitle(category);
        if (title == null) {
            return "";
        }
        Map<String, Object> optionValues = new LinkedHashMap<>();
        optionValues.put("mode", rawAttribute(attributes, "mode"));
        optionValues.put("hideprefix", rawAttribute(attributes, "hideprefix"));
        // 裸属性（<categorytree showcount>）与解析器函数的裸标记一样视为 true
        optionValues.put("showcount", booleanAttribute(attributes, "showcount"));
        optionValues.put("namespaces", rawAttribute(attributes, "namespaces"));
        optionValues.put("notranslations", booleanAttribute(attributes, "notranslations"));
        CategoryTreeOptions parsed = CategoryTreeOptions.parse(optionValues, this.config);
        int depth = parsed.capDepth(tagDepth(attributes));
        boolean hideRoot = CategoryTreeOptions.decodeBoolean(booleanAttribute(attributes, "hideroot"));

        Map<String, String> wrapper = CategoryTreeHtml.newAttributes();
        String userClass = attribute(attributes, "class");
        wrapper.put("class", userClass.isEmpty() ? TAG_CLASS : userClass + " " + TAG_CLASS);
        String style = attribute(attributes, "style");
        if (!style.isEmpty()) {
            wrapper.put("style", style);
        }
        String id = attribute(attributes, "id");
        if (!id.isEmpty()) {
            wrapper.put("id", id);
        }
        wrapper.put("data-ct-options", parsed.asJson());

        String inner;
        if (!categoryExists(title)) {
            inner = notice(this.messages.text("categorytree-not-found", category));
        } else if (hideRoot) {
            inner = renderChildren(title, depth, parsed);
        } else {
            inner = renderNode(title, depth, parsed);
        }
        return CategoryTreeHtml.element("div", wrapper, inner);
    }

    /**
     * 解析器函数（{@code {{#categorytree:分类|选项}}}}）的输出：一段 {@code <categorytree>} 标签文本。
     * <p>
     * core 的解析器函数返回值会按 wikitext 继续处理，直接把树 HTML 返回会被 HTML 白名单过滤掉
     * {@code data-ct-*} / {@code aria-*} 属性；因此这里改输出标签文本，由 core 在展开后用
     * {@link CategoryTreeNestedTag}（{@code expandBody=true}）把它提取为可信 HTML 块。
     */
    public String tagWikitext(Map<String, String> attributes, String categoryInput) {
        String category = sanitizeTagText(categoryInput == null ? "" : categoryInput.trim());
        if (category.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<categorytree");
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9-]*")) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : sanitizeTagText(entry.getValue());
                out.append(' ').append(key);
                if (!value.isEmpty()) {
                    out.append("=\"").append(CategoryTreeHtml.escapeAttribute(value)).append('"');
                }
            }
        }
        return out.append('>').append(category).append("</categorytree>").toString();
    }

    /** 去掉会破坏标签结构的尖括号（标题里本来也不允许出现）。 */
    private static String sanitizeTagText(String text) {
        return text.replace("<", "").replace(">", "").trim();
    }

    // ==================== 节点渲染 ====================

    /** 单个分类节点（对应 {@code CategoryTree::renderNode}）。 */
    public String renderNode(String categoryTitle, int depth, CategoryTreeOptions options) {
        CategoryCounts counts = categoryCounts(categoryTitle, options);
        return renderNodeInfo(categoryTitle, true, counts, depth, options);
    }

    /**
     * 分类的直接成员（对应 {@code CategoryTree::renderChildren}）：
     * 子分类在前、其它页面在后（各自保持 core 给出的排序键顺序），最多 {@code max-children} 个。
     * mode=parents 时返回该分类的上级分类（原版的反向查询）。
     */
    public String renderChildren(String categoryTitle, int depth, CategoryTreeOptions options) {
        if (!isCategoryTitle(categoryTitle)) {
            // 非分类命名空间的页面没有子节点
            return "";
        }
        if (options.isInverse()) {
            return renderParentNodes(categoryTitle, depth, options);
        }
        StringBuilder categories = new StringBuilder();
        StringBuilder others = new StringBuilder();
        for (CategoryMember member : members(categoryTitle, options)) {
            boolean isCategory = member.subcategory();
            CategoryCounts counts = isCategory ? categoryStore.counts(member.title()) : null;
            String html = renderNodeInfo(member.title(), isCategory, counts, depth - 1, options);
            if (isCategory) {
                categories.append(html);
            } else {
                others.append(html);
            }
        }
        return categories.append(others).toString();
    }

    /**
     * 分类的上级分类（对应 {@code CategoryTree::renderParents}）：
     * 每个上级分类是一个指向「特殊:分类树」的链接，用「 | 」分隔。
     */
    public String renderParents(String categoryTitle, CategoryTreeOptions options) {
        long pageId = pageIdOf(categoryTitle);
        if (pageId <= 0) {
            return "";
        }
        List<String> titles = this.categoryStore.categoriesOf(pageId);
        int limit = this.config.maxChildren();
        List<String> links = new ArrayList<>();
        for (String parent : titles) {
            if (limit > 0 && links.size() >= limit) {
                break;
            }
            if (!isCategoryTitle(parent)) {
                continue;
            }
            String label = unprefixed(parent);
            String url = specialPagePath() + "?" + specialPageQuery(parent, options);
            links.add(CategoryTreeHtml.element("span", attributesOf("class", "CategoryTreeItem"),
                    "<a class=\"CategoryTreeLabel\" href=\"" + CategoryTreeHtml.escapeAttribute(url)
                            + "\">" + CategoryTreeHtml.escapeText(label) + "</a>"));
        }
        return String.join(this.messages.pipeSeparator(), links);
    }

    /** 特殊页的树容器（对应 {@code CategoryTreePage::executeCategoryTree}，depth=1）。 */
    public String renderResultTree(String categoryTitle, CategoryTreeOptions options) {
        Map<String, String> wrapper = CategoryTreeHtml.newAttributes();
        wrapper.put("class", "CategoryTreeResult " + TAG_CLASS);
        wrapper.put("data-ct-options", options.asJson());
        return CategoryTreeHtml.element("div", wrapper, renderNode(categoryTitle, 1, options));
    }

    /** 分类不存在 / 其它提示：斜体的 {@code <span class="CategoryTreeNotice">}。 */
    public String notice(String message) {
        return CategoryTreeHtml.element("span", attributesOf("class", "CategoryTreeNotice"),
                message == null ? "" : message);
    }

    /** 「分类“X”不存在。」提示（特殊页用，对应 {@code categorytree-not-found}）。 */
    public String notFoundNotice(String categoryInput) {
        return notice(this.messages.text("categorytree-not-found",
                categoryInput == null ? "" : categoryInput.trim()));
    }

    // ==================== 内部实现 ====================

    /**
     * 单个节点的 HTML（对应 {@code CategoryTree::renderNodeInfo}）。
     *
     * @param title    节点标题（含命名空间）
     * @param isCategory 是否分类命名空间节点
     * @param counts   成员统计（{@code null} = 未知：反向模式或非分类节点，此时不判空、不显示计数）
     * @param children 还要往下展开几层（0 = 折叠且未加载，由 AJAX 展开）
     */
    private String renderNodeInfo(String title, boolean isCategory, CategoryCounts counts,
                                 int children, CategoryTreeOptions options) {
        // 深度下限保护：负数按「不展开」处理（原版负数会渲染成已加载但空的节点）
        children = Math.max(0, children);
        String mode = options.mode();
        boolean hidePrefix = resolveHidePrefix(options, isCategory);
        String label = hidePrefix ? unprefixed(title) : title;
        String link = CategoryTreeHtml.element("bdi", attributesOf("dir", DIR), pageLink(title, label));

        Integer count = null;
        if (isCategory && counts != null) {
            count = switch (mode) {
                case CategoryTreeOptions.MODE_CATEGORIES -> counts.subcategories();
                case CategoryTreeOptions.MODE_PAGES -> counts.all() - counts.files();
                default -> counts.all();
            };
        }

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"CategoryTreeSection\"><div class=\"CategoryTreeItem\">");
        if (isCategory) {
            if (count != null && count == 0) {
                // 空分类：不可展开的箭头
                out.append("<span class=\"CategoryTreeEmptyBullet\"></span>");
            } else {
                Map<String, String> toggle = CategoryTreeHtml.newAttributes();
                toggle.put("class", "CategoryTreeToggle");
                toggle.put("data-ct-title", unprefixed(title));
                if (children == 0) {
                    toggle.put("aria-expanded", "false");
                } else {
                    toggle.put("data-ct-loaded", "");
                    toggle.put("aria-expanded", "true");
                }
                // 注意：展开链接与 CategoryTreeChildren 之间必须保持「同一个 section 的直接子节点」关系
                out.append("<span class=\"CategoryTreeBullet\">")
                        .append(CategoryTreeHtml.element("a", toggle, "")).append(" </span>");
            }
        } else {
            out.append("<span class=\"CategoryTreePageBullet\"></span>");
        }
        out.append(' ').append(link);
        if (count != null && options.showCount()) {
            out.append(createCountString(counts));
        }
        out.append("</div><div class=\"CategoryTreeChildren\"");
        if (children == 0) {
            out.append(" style=\"display:none\"");
        }
        out.append('>');
        if (isCategory && children > 0) {
            String inner = renderChildren(title, children, options);
            if (inner.isEmpty()) {
                inner = CategoryTreeHtml.element("i", attributesOf("class", "CategoryTreeNotice"),
                        this.messages.text(emptyNoticeKey(mode)));
            }
            out.append(inner);
        }
        out.append("</div></div>");
        return out.toString();
    }

    /** 反向模式（mode=parents）的子节点：上级分类，统计信息不可用（与原版一致）。 */
    private String renderParentNodes(String categoryTitle, int depth, CategoryTreeOptions options) {
        long pageId = pageIdOf(categoryTitle);
        if (pageId <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int limit = this.config.maxChildren();
        int rendered = 0;
        for (String parent : this.categoryStore.categoriesOf(pageId)) {
            if (limit > 0 && rendered >= limit) {
                break;
            }
            if (!isCategoryTitle(parent)) {
                continue;
            }
            out.append(renderNodeInfo(parent, true, null, depth - 1, options));
            rendered++;
        }
        return out.toString();
    }

    /** 成员查询：不需要过滤时交给 core 的 limit（SQL 侧截断），需要过滤时全量取回再过滤 + 截断。 */
    private List<CategoryMember> members(String categoryTitle, CategoryTreeOptions options) {
        boolean filtering = !options.namespaces().isEmpty()
                || !CategoryTreeOptions.MODE_ALL.equals(options.mode());
        int limit = this.config.maxChildren();
        List<CategoryMember> all = this.categoryStore.members(categoryTitle, filtering ? 0 : limit);
        if (!filtering) {
            return all;
        }
        List<CategoryMember> result = new ArrayList<>();
        for (CategoryMember member : all) {
            if (!matches(member, options)) {
                continue;
            }
            result.add(member);
            if (limit > 0 && result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /** mode / namespaces 过滤（对应原版 SQL 里的 cl_type 与 page_namespace 条件）。 */
    private static boolean matches(CategoryMember member, CategoryTreeOptions options) {
        if (!options.namespaces().isEmpty()) {
            return options.namespaces().contains(member.namespaceId());
        }
        return switch (options.mode()) {
            case CategoryTreeOptions.MODE_CATEGORIES -> member.subcategory();
            case CategoryTreeOptions.MODE_PAGES -> !member.file();
            default -> true;
        };
    }

    /** 分类节点的成员统计；反向模式下原版不查 category 表，返回 {@code null}。 */
    private CategoryCounts categoryCounts(String categoryTitle, CategoryTreeOptions options) {
        if (options.isInverse() || !isCategoryTitle(categoryTitle)) {
            return null;
        }
        return this.categoryStore.counts(categoryTitle);
    }

    /**
     * 计数 span（对应 {@code CategoryTree::createCountString}）：
     * title 用「含有 N 个子分类、N 个页面和 N 个文件」，内容是「(N个分类、N个页面)」这类短形式（空分类显示「空」）。
     */
    private String createCountString(CategoryCounts counts) {
        String title = this.messages.text("categorytree-member-counts",
                counts.subcategories(), counts.pages(), counts.files());
        List<String> numbers = new ArrayList<>();
        if (counts.subcategories() > 0) {
            numbers.add(this.messages.text("categorytree-num-categories", counts.subcategories()));
        }
        if (counts.pages() > 0) {
            numbers.add(this.messages.text("categorytree-num-pages", counts.pages()));
        }
        if (counts.files() > 0) {
            numbers.add(this.messages.text("categorytree-num-files", counts.files()));
        }
        String shortForm = numbers.isEmpty()
                ? this.messages.text("categorytree-num-empty")
                : this.messages.joinList(numbers);
        // 原版的 categorytree-member-num 用 $5（参数 1-4 只给自定义消息用）
        String content = this.messages.textRaw("categorytree-member-num",
                counts.subcategories(), counts.pages(), counts.files(), counts.all(), shortForm);
        Map<String, String> attributes = CategoryTreeHtml.newAttributes();
        attributes.put("title", title);
        attributes.put("dir", DIR);
        return " " + CategoryTreeHtml.element("span", attributes, CategoryTreeHtml.escapeText(content));
    }

    /** 空节点提示消息键（对应原版 renderNodeInfo 里的 match）。 */
    private static String emptyNoticeKey(String mode) {
        return switch (mode) {
            case CategoryTreeOptions.MODE_CATEGORIES -> "categorytree-no-subcategories";
            case CategoryTreeOptions.MODE_PAGES -> "categorytree-no-pages";
            case CategoryTreeOptions.MODE_PARENTS -> "categorytree-no-parent-categories";
            default -> "categorytree-nothing-found";
        };
    }

    /** 是否隐藏命名空间前缀（对应原版 renderNodeInfo 的 hideprefix 判定）。 */
    private static boolean resolveHidePrefix(CategoryTreeOptions options, boolean isCategory) {
        return switch (options.hidePrefix()) {
            case CategoryTreeOptions.HIDE_PREFIX_ALWAYS -> true;
            // auto：只在「只显示分类」时隐藏
            case CategoryTreeOptions.HIDE_PREFIX_AUTO ->
                    CategoryTreeOptions.MODE_CATEGORIES.equals(options.mode());
            case CategoryTreeOptions.HIDE_PREFIX_CATEGORIES -> isCategory;
            default -> false;
        };
    }

    /** 页面链接（红链用 core 的 {@code new} 类样式）。 */
    private String pageLink(String title, String label) {
        String href = WikiUrls.pagePrefix() + title.replace(' ', '_');
        return "<a" + (categoryExists(title) ? "" : " class=\"new\"") + " href=\""
                + CategoryTreeHtml.escapeAttribute(href) + "\">"
                + CategoryTreeHtml.escapeText(label) + "</a>";
    }

    /** 「特殊:分类树」的查询串：target + 归一化后的选项（与原版 renderParents 的查询参数一致）。 */
    private String specialPageQuery(String categoryTitle, CategoryTreeOptions options) {
        StringBuilder query = new StringBuilder();
        query.append("target=").append(encode(categoryTitle));
        query.append("&mode=").append(encode(options.mode()));
        if (!CategoryTreeOptions.HIDE_PREFIX_CATEGORIES.equals(options.hidePrefix())) {
            query.append("&hideprefix=").append(encode(options.hidePrefix()));
        }
        if (options.showCount()) {
            query.append("&showcount=1");
        }
        if (!options.namespaces().isEmpty()) {
            query.append("&namespaces=").append(encode(
                    String.join("|", options.namespaces().stream().map(String::valueOf).toList())));
        }
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    // ==================== 标题与查询辅助 ====================

    /**
     * 用户输入 → 分类标题（对应 {@code CategoryTree::makeTitle}）：
     * 忽略开头的「分类:」/「Category:」；形如「Wikipedia:Foo」的输入强制当作分类名；
     * 空串返回 {@code null}。{@code _} 归一成空格。
     */
    public static String makeCategoryTitle(String input) {
        String value = input == null ? "" : input.trim().replace('_', ' ');
        if (value.isEmpty()) {
            return null;
        }
        int colon = value.indexOf(':');
        String name = value;
        if (colon > 0) {
            String prefix = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if ("分类".equals(prefix) || "category".equals(prefix)) {
                name = value.substring(colon + 1);
            }
        }
        name = name.trim();
        if (name.isEmpty()) {
            return null;
        }
        return CATEGORY_PREFIX + name;
    }

    /** 是否分类命名空间标题（{@code 分类:X} / {@code Category:X}）。 */
    public static boolean isCategoryTitle(String title) {
        if (title == null) {
            return false;
        }
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("分类:") || value.startsWith("category:");
    }

    /** 去掉命名空间前缀（标签显示用）。 */
    public static String unprefixed(String title) {
        if (title == null) {
            return "";
        }
        int colon = title.indexOf(':');
        return colon > 0 ? title.substring(colon + 1).trim() : title.trim();
    }

    /** 分类页面是否存在（对应原版 {@code $title->getArticleID()} 判定）。 */
    public boolean categoryExists(String categoryTitle) {
        String title = categoryTitle == null ? null : categoryTitle.trim();
        if (title == null || title.isEmpty()) {
            return false;
        }
        return this.pageStore.byTitle(title).isPresent();
    }

    private long pageIdOf(String categoryTitle) {
        return this.pageStore.byTitle(categoryTitle).map(snapshot -> snapshot.id()).orElse(0L);
    }

    /** 标签的 depth 属性：非数字按 0 处理（对齐 PHP 的 {@code (int)} 转换）。 */
    private static int tagDepth(Map<String, String> attributes) {
        String value = attribute(attributes, "depth");
        if (value.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * 布尔属性：属性不存在返回 {@code null}（用默认值）；
     * 存在但没有值（{@code <categorytree showcount>}）返回 {@code "true"}；其余返回原值。
     */
    private static String booleanAttribute(Map<String, String> attributes, String name) {
        String value = rawAttribute(attributes, name);
        if (value == null) {
            return null;
        }
        return value.isEmpty() ? "true" : value;
    }

    /** 原始属性值；属性不存在返回 {@code null}。 */
    private static String rawAttribute(Map<String, String> attributes, String name) {
        return attributes == null ? null : attributes.get(name);
    }

    private static String attribute(Map<String, String> attributes, String name) {
        String value = attributes == null ? null : attributes.get(name);
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> attributesOf(String name, String value) {
        Map<String, String> attributes = CategoryTreeHtml.newAttributes();
        attributes.put(name, value);
        return attributes;
    }
}
