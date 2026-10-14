package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code HtmlHeadProvider}：只在页面用到分类树时插入 JS / CSS。 */
class CategoryTreeHeadProviderTest {

    private final CategoryTreeHeadProvider provider =
            new CategoryTreeHeadProvider(new CategoryTreeMessages(Locale.SIMPLIFIED_CHINESE));

    @Test
    void injectsAssetsOnlyWhenPageUsesCategoryTree() {
        assertEquals(List.of(), provider.headItems(context(Map.of())));
        assertEquals(List.of(), provider.headItems(context(Map.of("notitle", "1"))));
        assertEquals(List.of(), provider.headItems(null));
    }

    @Test
    void injectsMessagesStyleAndScript() {
        List<String> items = provider.headItems(context(Map.of(CategoryTreeRenderer.USED_PROPERTY, "1")));

        assertEquals(3, items.size());
        assertTrue(items.get(0).startsWith("<script>window.CategoryTreeMessages = {"), items.get(0));
        assertTrue(items.get(0).contains("\"expand\":\"展开\""), items.get(0));
        assertEquals("<link rel=\"stylesheet\" href=\"/plugins/category-tree/category-tree.css\">", items.get(1));
        assertEquals("<script defer src=\"/plugins/category-tree/ext.categoryTree.js\"></script>", items.get(2));
    }

    @Test
    void englishMessagesUseEnglishFallback() {
        CategoryTreeHeadProvider english =
                new CategoryTreeHeadProvider(new CategoryTreeMessages(Locale.ENGLISH));
        List<String> items = english.headItems(context(Map.of(CategoryTreeRenderer.USED_PROPERTY, "1")));
        assertTrue(items.get(0).contains("\"expand\":\"expand\""), items.get(0));
    }

    private static HeadContext context(Map<String, String> properties) {
        return new HeadContext() {
            @Override
            public long pageId() {
                return 1;
            }

            @Override
            public String pageTitle() {
                return "页面";
            }

            @Override
            public String path() {
                return "/wiki/页面";
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Map<String, String> properties() {
                return new LinkedHashMap<>(properties);
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost";
            }

            @Override
            public String fileUrl(String fileName) {
                return "";
            }

            @Override
            public int fileWidth(String fileName) {
                return 0;
            }

            @Override
            public int fileHeight(String fileName) {
                return 0;
            }
        };
    }
}
