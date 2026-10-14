package cc.thonly.wikikernel.plugin.categorytree.web;

import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeCategoryStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakePageStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeTag;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertContains;
import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertNotContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「特殊:分类树」的模型填充（移植 {@code CategoryTreePage}）。 */
class CategoryTreeSpecialPageTest {

    private final FakeCategoryStore store = CategoryTreeFakes.demoStore();
    private final FakePageStore pages = CategoryTreeFakes.demoPages();
    private final CategoryTreeRenderer renderer = CategoryTreeFakes.renderer(store, pages);
    private final CategoryTreeConfig config = CategoryTreeConfig.defaults();
    private final CategoryTreeSpecialPage page = new CategoryTreeSpecialPage(renderer, config,
            new CategoryTreeMessages(Locale.SIMPLIFIED_CHINESE));

    private Map<String, Object> populate(Map<String, String> parameters) {
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest(page.name(), parameters), model);
        return model;
    }

    @Test
    void pageMetadata() {
        assertEquals("分类树", page.name());
        assertEquals("分类树", page.displayName());
        assertEquals(List.of("CategoryTree"), page.aliases());
        assertEquals(SpecialPageCategories.WIKI, page.category());
        assertEquals("special/category-tree", page.template());
    }

    @Test
    void formOnlyWithoutTarget() {
        Map<String, Object> model = populate(Map.of());

        assertContains("请输入一个分类名称", String.valueOf(model.get("intro")));
        assertEquals("", model.get("target"));
        assertEquals(false, model.get("showcount"));
        assertEquals(3, ((List<?>) model.get("modeOptions")).size());
        assertContains("{\"expand\":", String.valueOf(model.get("messagesJson")));
        assertFalse(model.containsKey("treeHtml"));
        assertFalse(model.containsKey("noticeHtml"));
        assertFalse(model.containsKey("parentsHtml"));
    }

    @Test
    void targetRendersParentsAndTree() {
        Map<String, Object> model = populate(Map.of("target", "甲"));

        assertEquals("甲", model.get("target"));
        String parents = String.valueOf(model.get("parentsHtml"));
        assertContains("上级分类：", parents);
        assertContains("CategoryTreeLabel", parents);

        String tree = String.valueOf(model.get("treeHtml"));
        assertContains("<div class=\"CategoryTreeResult CategoryTreeTag\" data-ct-options=\"{", tree);
        assertContains("<a href=\"/wiki/分类:子一\">子一</a>", tree);
        // depth=1：子分类已加载但没有 renderChildren 之外的内容
        assertContains("data-ct-loaded", tree);
        // 特殊页默认不显示成员计数（default-showcount=false）
        assertNotContains("<span title=", tree);
    }

    @Test
    void subpageSyntaxBehavesLikeTargetParameter() {
        assertEquals(populate(Map.of("target", "甲")).get("treeHtml"),
                populate(Map.of("name", "甲")).get("treeHtml"));
        assertEquals(populate(Map.of("target", "甲")).get("treeHtml"),
                populate(Map.of("page", "甲")).get("treeHtml"));
    }

    @Test
    void missingCategoryShowsNotice() {
        Map<String, Object> model = populate(Map.of("target", "不存在的分类"));

        assertContains("分类“不存在的分类”不存在。", String.valueOf(model.get("noticeHtml")));
        assertFalse(model.containsKey("treeHtml"));
    }

    @Test
    void namespacesAndShowCountArePassedToTreeOptions() {
        Map<String, Object> filesOnly = populate(Map.of("target", "甲", "namespaces", "6"));
        assertContains("文件:图.png", String.valueOf(filesOnly.get("treeHtml")));
        // 设置了命名空间过滤时表单模式固定显示「所有页面」（原版行为）
        assertTrue(selectedMode(filesOnly).equals("all"));

        Map<String, Object> counts = populate(Map.of("target", "甲", "showcount", "1", "mode", "all"));
        assertContains("含有 2 个子分类、1 个页面和 1 个文件", String.valueOf(counts.get("treeHtml")));
    }

    @Test
    void modeParameterSelectsFormDefault() {
        assertEquals("pages", selectedMode(populate(Map.of("target", "甲", "mode", "pages"))));
        assertEquals("categories", selectedMode(populate(Map.of("target", "甲"))));
        // 旧数值写法也接受
        assertEquals("pages", selectedMode(populate(Map.of("target", "甲", "mode", "10"))));
    }

    private static String selectedMode(Map<String, Object> model) {
        for (Object item : (List<?>) model.get("modeOptions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> option = (Map<String, Object>) item;
            if (Boolean.TRUE.equals(option.get("selected"))) {
                return String.valueOf(option.get("value"));
            }
        }
        return "";
    }
}
