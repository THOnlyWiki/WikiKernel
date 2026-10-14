package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeFunctionCall;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakePageStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeRenderContext;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeTagCall;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertContains;
import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertNotContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 解析入口：{@code <categorytree>} 标签与 {@code {{#categorytree}}} 解析器函数。 */
class CategoryTreeParserEntryTest {

    private final CategoryTreeFakes.FakeCategoryStore store = CategoryTreeFakes.demoStore();
    private final FakePageStore pages = CategoryTreeFakes.demoPages();
    private final CategoryTreeRenderer renderer = CategoryTreeFakes.renderer(store, pages);
    private final CategoryTreeTag tag = new CategoryTreeTag(renderer);
    private final CategoryTreeNestedTag nestedTag = new CategoryTreeNestedTag(renderer);
    private final CategoryTreeFunction function = new CategoryTreeFunction(renderer);

    @Test
    void tagRendersTreeAndMarksPageAsUsingCategoryTree() {
        FakeRenderContext context = new FakeRenderContext("页面");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "all");
        attributes.put("depth", "1");
        String html = tag.render(new FakeTagCall(context, attributes, " 甲 "));

        assertContains("CategoryTreeTag", html);
        assertContains("页面一", html);
        assertEquals("1", context.property(CategoryTreeRenderer.USED_PROPERTY));
        assertEquals("categorytree", tag.name());
        assertEquals(false, tag.expandBody());
    }

    @Test
    void nestedTagHandlesExpandedBodies() {
        FakeRenderContext context = new FakeRenderContext("页面");
        String html = nestedTag.render(new FakeTagCall(context, Map.of("depth", "1"), "分类:甲"));

        assertTrue(nestedTag.expandBody());
        assertContains("<a href=\"/wiki/分类:子一\">子一</a>", html);
        assertEquals("1", context.property(CategoryTreeRenderer.USED_PROPERTY));
    }

    @Test
    void tagBodyIsTheCategoryName() {
        // 标签内容不用展开：{{模板}} 原样作为分类名（分类不存在 → 提示）
        FakeRenderContext context = new FakeRenderContext("页面");
        String html = tag.render(new FakeTagCall(context, Map.of(), "{{模板}}"));
        assertContains("分类“{{模板}}”不存在。", html);
    }

    @Test
    void functionEmitsEquivalentTagWikitext() {
        FakeRenderContext context = new FakeRenderContext("页面");
        String wikitext = function.invoke(new FakeFunctionCall(context, List.of("甲", "mode=all", "depth=2")));

        assertEquals("<categorytree mode=\"all\" depth=\"2\">甲</categorytree>", wikitext);
        assertEquals("categorytree", function.name());
        assertTrue(function.aliases().isEmpty());
    }

    @Test
    void functionHandlesBareFlagsAndQuotedValues() {
        FakeRenderContext context = new FakeRenderContext("页面");

        String bare = function.invoke(new FakeFunctionCall(context, List.of("甲", "hideroot", "showcount")));
        assertEquals("<categorytree hideroot=\"true\" showcount=\"true\">甲</categorytree>", bare);

        String quoted = function.invoke(new FakeFunctionCall(context,
                List.of("甲", "mode=\"all\"", "hideprefix= \"never\" ", " namespaces = 0|14 ")));
        assertEquals("<categorytree mode=\"all\" hideprefix=\"never\" namespaces=\"0|14\">甲</categorytree>", quoted);
    }

    @Test
    void functionExpandsArguments() {
        FakeRenderContext context = new FakeRenderContext("页面");
        String wikitext = function.invoke(new FakeFunctionCall(context,
                List.of("{{{1}}}", "mode={{{模式}}}"),
                CategoryTreeFakes.frame(Map.of("{{{1}}}", "甲", "{{{模式}}}", "all"))));

        assertEquals("<categorytree mode=\"all\">甲</categorytree>", wikitext);
    }

    @Test
    void functionWithEmptyCategoryEmitsNothing() {
        FakeRenderContext context = new FakeRenderContext("页面");
        assertEquals("", function.invoke(new FakeFunctionCall(context, List.of())));
        assertEquals("", function.invoke(new FakeFunctionCall(context, List.of(""))));
    }

    @Test
    void functionSanitizesTagBreakingCharacters() {
        FakeRenderContext context = new FakeRenderContext("页面");
        String wikitext = function.invoke(new FakeFunctionCall(context,
                List.of("甲</categorytree><script>x</script>", "class=<b>不安全</b>")));

        assertNotContains("</categorytree><script>", wikitext);
        assertContains("</categorytree>", wikitext);
        assertNotContains("<b>", wikitext);
    }
}
