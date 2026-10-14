package cc.thonly.wikikernel.plugin.categorytree.service;

import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeCategoryStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakePageStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeTag;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertContains;
import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertCount;
import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertNotContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 树 HTML 结构（对齐原版 {@code CategoryTree::getTag / renderNode / renderChildren / renderParents}），
 * 契约必须与 {@code ext.categoryTree.js} / {@code category-tree.css} 一致。
 */
class CategoryTreeRendererTest {

    private final FakeCategoryStore store = CategoryTreeFakes.demoStore();
    private final FakePageStore pages = CategoryTreeFakes.demoPages();
    private final CategoryTreeRenderer renderer = CategoryTreeFakes.renderer(store, pages);

    private CategoryTreeOptions options(String... pairs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw.put(pairs[i], pairs[i + 1]);
        }
        return CategoryTreeOptions.parse(raw, CategoryTreeConfig.defaults());
    }

    @Test
    void tagRendersRootNodeAndChildren() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("class", "custom");
        attributes.put("id", "树");
        attributes.put("depth", "1");
        String html = renderer.renderTag(attributes, "甲");

        // data-ct-options 是 HTML 属性值（&quot; 由浏览器还原成 JSON）
        assertContains("<div class=\"custom CategoryTreeTag\" id=\"树\" data-ct-options=\"", html);
        assertContains("&quot;mode&quot;:&quot;categories&quot;", html);
        assertContains("<div class=\"CategoryTreeSection\">", html);
        // 根节点：分类:甲，前缀被默认的 hideprefix=categories 隐藏；depth=1 → 已加载且展开
        assertContains("<a class=\"CategoryTreeToggle\" data-ct-title=\"甲\" data-ct-loaded "
                + "aria-expanded=\"true\"></a>", html);
        assertContains("<a href=\"/wiki/分类:甲\">甲</a>", html);
        // 直接子节点在 depth=1 时是「已折叠、未加载」
        assertContains("<a class=\"CategoryTreeToggle\" data-ct-title=\"子一\" aria-expanded=\"false\"></a>", html);
        assertNotContains("data-ct-title=\"子一\" data-ct-loaded", html);
        // 子分类在前、其它页面在后；mode=categories 时不含页面
        assertContains("<a href=\"/wiki/分类:子一\">子一</a>", html);
        assertContains("<a href=\"/wiki/分类:子二\">子二</a>", html);
        assertNotContains("页面一", html);
        assertTrue(html.indexOf("子一") < html.indexOf("子二"), html);
    }

    @Test
    void depthZeroHidesChildrenAndMarksCollapsed() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("depth", "0");
        String html = renderer.renderTag(attributes, "分类:甲");

        assertContains("<a class=\"CategoryTreeToggle\" data-ct-title=\"甲\" aria-expanded=\"false\"", html);
        assertContains("<div class=\"CategoryTreeChildren\" style=\"display:none\">", html);
        assertNotContains("子一", html);
    }

    @Test
    void depthTwoRendersGrandChildren() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("depth", "2");
        String html = renderer.renderTag(attributes, "甲");

        // 子一（有子分类 孙一）已加载 + 展开
        assertContains("<a class=\"CategoryTreeToggle\" data-ct-title=\"子一\" data-ct-loaded "
                + "aria-expanded=\"true\"></a>", html);
        // mode=categories：孙一 是子分类、页面二 不属于该模式
        assertContains("<a href=\"/wiki/分类:孙一\">孙一</a>", html);
        assertNotContains("页面二", html);
        // 孙一 没有成员 → 空箭头（第三层不再展开）
        assertContains("<span class=\"CategoryTreeEmptyBullet\"></span> <bdi dir=\"ltr\">"
                + "<a href=\"/wiki/分类:孙一\">孙一</a></bdi>", html);
    }

    @Test
    void depthIsCappedPerMode() {
        // categories 默认上限 2：depth=9 的效果与 depth=2 相同
        assertEquals(renderer.renderTag(Map.of("depth", "2"), "甲"),
                renderer.renderTag(Map.of("depth", "9"), "甲"));
        // pages 模式上限 1：depth=9 的效果与 depth=1 相同
        assertEquals(renderer.renderTag(Map.of("mode", "pages", "depth", "1"), "甲"),
                renderer.renderTag(Map.of("mode", "pages", "depth", "9"), "甲"));
    }

    @Test
    void hideRootRendersChildrenOnly() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("hideroot", "");
        String html = renderer.renderTag(attributes, "甲");

        assertContains("CategoryTreeTag", html);
        assertContains("<a href=\"/wiki/分类:子一\">子一</a>", html);
        // 根节点本体不应出现
        assertNotContains("<a href=\"/wiki/分类:甲\">", html);
    }

    @Test
    void subcategoriesComeBeforePagesAndFilesInAllMode() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "all");
        attributes.put("hideprefix", "never");
        String html = renderer.renderTag(attributes, "甲");

        // all：子分类 + 页面 + 文件都渲染，且子分类在最前
        assertTrue(html.indexOf("分类:子一") < html.indexOf(">页面一<"), html);
        assertTrue(html.indexOf(">页面一<") < html.indexOf("文件:图.png"), html);
        assertContains("<a href=\"/wiki/页面一\">页面一</a>", html);
        assertContains("<a href=\"/wiki/文件:图.png\">文件:图.png</a>", html);
    }

    @Test
    void pagesModeSkipsFiles() {
        String html = renderer.renderTag(Map.of("mode", "pages", "hideprefix", "never"), "甲");
        assertContains("页面一", html);
        assertNotContains("文件:图.png", html);
    }

    @Test
    void namespacesFilterWins() {
        String html = renderer.renderTag(Map.of("namespaces", "6"), "甲");
        assertContains("文件:图.png", html);
        assertNotContains("页面一", html);
        assertNotContains("分类:子一", html);
    }

    @Test
    void showCountAddsCountSpanWithTitle() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "all");
        attributes.put("showcount", "");
        String html = renderer.renderTag(attributes, "甲");

        assertContains("<span title=\"含有 2 个子分类、1 个页面和 1 个文件\" dir=\"ltr\">"
                + "(2个分类、1个页面、1个文件)</span>", html);
        // 完全没有成员时用 categorytree-num-empty（「空」）：子二 一个成员都没有
        assertContains("<span title=\"含有 0 个子分类、0 个页面和 0 个文件\" dir=\"ltr\">(空)</span>", html);

        // mode=categories 时计数只看子分类，短形式只列非零项
        String categories = renderer.renderTag(Map.of("showcount", "", "depth", "1"), "甲");
        assertContains("<span title=\"含有 1 个子分类、1 个页面和 0 个文件\" dir=\"ltr\">(1个分类、1个页面)</span>",
                categories);
    }

    @Test
    void emptyCategoryGetsEmptyBullet() {
        String html = renderer.renderTag(Map.of("depth", "0"), "子二");
        assertContains("<span class=\"CategoryTreeEmptyBullet\"></span>", html);
        assertNotContains("CategoryTreeToggle", html);
    }

    @Test
    void emptyCategoryShowsNoticeWhenExpanded() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("depth", "1");
        String html = renderer.renderTag(attributes, "子二");
        assertContains("<i class=\"CategoryTreeNotice\">无子分类</i>", html);

        String pagesOnly = renderer.renderTag(Map.of("mode", "pages", "depth", "1"), "子二");
        assertContains("<i class=\"CategoryTreeNotice\">没有页面或子分类</i>", pagesOnly);
    }

    @Test
    void missingCategoryShowsNotFoundNotice() {
        String html = renderer.renderTag(Map.of(), "不存在的分类");
        assertContains("<span class=\"CategoryTreeNotice\">分类“不存在的分类”不存在。</span>", html);
        assertContains("data-ct-options=", html);
        assertNotContains("CategoryTreeSection", html);
    }

    @Test
    void emptyCategoryNameRendersNothing() {
        assertEquals("", renderer.renderTag(Map.of(), "   "));
        assertEquals("", renderer.renderTag(Map.of(), null));
    }

    @Test
    void parentsModeReturnsParentCategoriesAsChildren() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "parents");
        attributes.put("showcount", "");
        String html = renderer.renderTag(attributes, "甲");

        assertContains("<a href=\"/wiki/分类:父一\">父一</a>", html);
        assertContains("<a href=\"/wiki/分类:父二\">父二</a>", html);
        // 反向模式下原版不查 category 表：即使 showcount 打开也不输出成员计数
        assertNotContains("含有", html);
        assertNotContains("<span title=", html);
    }

    @Test
    void renderParentsBuildsSpecialPageLinks() {
        String html = renderer.renderParents("分类:甲", options("mode", "parents"));

        assertContains("<span class=\"CategoryTreeItem\"><a class=\"CategoryTreeLabel\" "
                + "href=\"/wiki/特殊:分类树?target=%E5%88%86%E7%B1%BB%3A%E7%88%B6%E4%B8%80&amp;mode=parents\">父一</a></span>",
                html);
        assertContains(" | ", html);
    }

    @Test
    void renderParentsIsEmptyWithoutPage() {
        assertEquals("", renderer.renderParents("分类:不存在", options("mode", "parents")));
    }

    @Test
    void maxChildrenLimitsMembers() {
        // mode=all 不需要过滤：直接把 max-children 交给 core（SQL 侧 LIMIT）
        CategoryTreeConfig config = new CategoryTreeConfig(true, 2, 2, 1, 1,
                CategoryTreeOptions.MODE_CATEGORIES, CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, false);
        CategoryTreeRenderer limited = new CategoryTreeRenderer(store, pages, config,
                new CategoryTreeMessages(Locale.SIMPLIFIED_CHINESE));
        String all = limited.renderTag(Map.of("mode", "all", "depth", "1"), "甲");
        assertTrue(store.limits.contains(2), String.valueOf(store.limits));
        assertContains(">子一</a>", all);
        assertNotContains("页面一", all);

        // mode=categories 需要按类型过滤：先全量取回（limit=0）、过滤后再按 max-children 截断
        String categories = limited.renderTag(Map.of("depth", "1"), "甲");
        assertTrue(store.limits.contains(0), String.valueOf(store.limits));
        assertContains(">子一</a>", categories);
        assertContains(">子二</a>", categories);
        assertNotContains("页面一", categories);
    }

    @Test
    void maxChildrenOneKeepsFirstSubcategoryOnly() {
        CategoryTreeConfig config = new CategoryTreeConfig(true, 1, 2, 1, 1,
                CategoryTreeOptions.MODE_CATEGORIES, CategoryTreeOptions.HIDE_PREFIX_CATEGORIES, false);
        CategoryTreeRenderer limited = new CategoryTreeRenderer(store, pages, config,
                new CategoryTreeMessages(Locale.SIMPLIFIED_CHINESE));
        String html = limited.renderTag(Map.of("depth", "1"), "甲");

        assertContains(">子一</a>", html);
        assertNotContains(">子二</a>", html);
    }

    @Test
    void labelPrefixedWhenHidePrefixNever() {
        String html = renderer.renderTag(Map.of("hideprefix", "never", "depth", "0"), "甲");
        assertContains("<a href=\"/wiki/分类:甲\">分类:甲</a>", html);
    }

    @Test
    void pageBulletForNonCategoryMembers() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "pages");
        attributes.put("depth", "1");
        String html = renderer.renderTag(attributes, "子一");
        assertContains("<span class=\"CategoryTreePageBullet\"></span> <bdi dir=\"ltr\"><a href=\"/wiki/页面二\">页面二</a></bdi>",
                html);
    }

    @Test
    void resultTreeForSpecialPage() {
        String html = renderer.renderResultTree("分类:甲", options("depth", "1"));
        assertContains("<div class=\"CategoryTreeResult CategoryTreeTag\" data-ct-options=\"{", html);
        assertContains("CategoryTreeSection", html);
    }

    @Test
    void makeCategoryTitleIgnoresNamespacePrefix() {
        assertEquals("分类:甲", CategoryTreeRenderer.makeCategoryTitle("甲"));
        assertEquals("分类:甲", CategoryTreeRenderer.makeCategoryTitle(" 分类:甲 "));
        assertEquals("分类:甲", CategoryTreeRenderer.makeCategoryTitle("Category:甲"));
        // 下划线按 MediaWiki 的标题归一化变成空格（链接里仍写成下划线）
        assertEquals("分类:甲 乙", CategoryTreeRenderer.makeCategoryTitle("甲_乙"));
        // 其它命名空间前缀不当作分类前缀，整体作为分类名（原版行为）
        assertEquals("分类:条目:甲", CategoryTreeRenderer.makeCategoryTitle("条目:甲"));
        assertEquals(null, CategoryTreeRenderer.makeCategoryTitle("  "));
        assertEquals(null, CategoryTreeRenderer.makeCategoryTitle("分类:"));
        assertTrue(CategoryTreeRenderer.isCategoryTitle("Category:甲"));
        assertFalse(CategoryTreeRenderer.isCategoryTitle("甲"));
    }

    @Test
    void renderChildrenHandlesNonCategoryTitles() {
        assertEquals("", renderer.renderChildren("页面一", 1, options()));
        assertEquals("", renderer.renderChildren(null, 1, options()));
    }

    @Test
    void attributesAreEscaped() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("class", "x\"><script>alert(1)</script>");
        String html = renderer.renderTag(attributes, "甲");
        assertNotContains("<script>alert(1)</script>", html);
        assertContains("&lt;script&gt;", html);
    }
}
