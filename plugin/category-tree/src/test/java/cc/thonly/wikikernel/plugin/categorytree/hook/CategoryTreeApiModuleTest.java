package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.ActionApiException;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeConfig;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeApiRequest;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakeCategoryStore;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.FakePageStore;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertContains;
import static cc.thonly.wikikernel.plugin.categorytree.CategoryTreeFakes.assertNotContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code action=categorytree}（移植 {@code ApiCategoryTree}）。 */
class CategoryTreeApiModuleTest {

    private final FakeCategoryStore store = CategoryTreeFakes.demoStore();
    private final FakePageStore pages = CategoryTreeFakes.demoPages();
    private final CategoryTreeRenderer renderer = CategoryTreeFakes.renderer(store, pages);
    private final CategoryTreeApiModule api = new CategoryTreeApiModule(renderer, CategoryTreeConfig.defaults());

    private static FakeApiRequest request(String category, String options) {
        return new FakeApiRequest(options == null
                ? Map.of("action", "categorytree", "category", category)
                : Map.of("action", "categorytree", "category", category, "options", options));
    }

    @SuppressWarnings("unchecked")
    private static String html(Object result) {
        Map<String, Object> tree = (Map<String, Object>) ((Map<String, Object>) result).get("categorytree");
        return String.valueOf(tree.get("html"));
    }

    @Test
    void returnsHtmlInsideCategorytreeKey() {
        Map<String, Object> result = (Map<String, Object>) api.handle(request("甲", null));
        assertEquals(List.of("categorytree"), List.copyOf(result.keySet()));
        String html = html(result);
        assertContains("<a href=\"/wiki/分类:子一\">子一</a>", html);
        assertNotContains("CategoryTreeTag", html);
    }

    @Test
    void optionsControlModeAndDepth() {
        // categories 的深度上限是 2：depth=2 能看到子分类 子一 之下的 孙一
        String html = html(api.handle(request("甲", "{\"mode\":\"categories\",\"depth\":2}")));
        assertContains("<a href=\"/wiki/分类:孙一\">孙一</a>", html);

        String all = html(api.handle(request("甲", "{\"mode\":\"all\",\"depth\":1}")));
        assertContains("文件:图.png", all);

        // depth=0：只渲染直接成员，子节点不再往下展开
        String shallow = html(api.handle(request("甲", "{\"mode\":\"categories\",\"depth\":0}")));
        assertContains("子一", shallow);
        assertNotContains("孙一", shallow);
    }

    @Test
    void depthIsCappedByMode() {
        // pages 模式上限 1：depth=9 不会展开第二层
        assertEquals(html(api.handle(request("甲", "{\"mode\":\"pages\",\"depth\":1}"))),
                html(api.handle(request("甲", "{\"mode\":\"pages\",\"depth\":9}"))));
    }

    @Test
    void emptyHtmlWhenCategoryHasNoMembers() {
        String html = html(api.handle(request("子二", null)));
        assertEquals("", html);
    }

    @Test
    void emptyOptionsBehaveLikeDefaults() {
        assertEquals(html(api.handle(request("甲", null))), html(api.handle(request("甲", ""))));
    }

    @Test
    void nonObjectJsonIsRejected() {
        for (String options : List.of("[1,2]", "\"文本\"", "5", "true", "{坏 JSON}", "{\"mode\":}")) {
            ActionApiException error = assertThrows(ActionApiException.class,
                    () -> api.handle(request("甲", options)), options);
            assertEquals("invalidjson", error.code(), options);
        }
    }

    @Test
    void nonScalarOptionValuesAreRejectedExceptNamespaces() {
        ActionApiException error = assertThrows(ActionApiException.class,
                () -> api.handle(request("甲", "{\"mode\":{}}")));
        assertEquals("invalidjson-option", error.code());
        assertEquals("选项“mode”包含无效值。", error.info());

        ActionApiException listError = assertThrows(ActionApiException.class,
                () -> api.handle(request("甲", "{\"mode\":[1,2]}")));
        assertEquals("invalidjson-option", listError.code());

        // namespaces 允许数组
        String html = html(api.handle(request("甲", "{\"namespaces\":[14]}")));
        assertContains("分类:子一", html);
        assertNotContains("页面一", html);
    }

    @Test
    void missingCategoryIsRejected() {
        ActionApiException missing = assertThrows(ActionApiException.class,
                () -> api.handle(new FakeApiRequest(Map.of("action", "categorytree"))));
        assertEquals("missingparam", missing.code());
        assertContains("category", missing.info());
    }

    @Test
    void actionNameIsCategorytree() {
        assertEquals("categorytree", api.action());
    }
}
