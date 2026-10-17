package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PageSearchStore;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageQuery;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DplFunctionTest {

    @Test
    void rendersHeaderAndDefaultList() {
        FakeStore store = new FakeStore(List.of(row("页面甲"), row("页面乙")));
        DplFunction function = new DplFunction(store);
        String result = function.invoke(call(
                "namespace=",
                "count=8",
                "ordermethod=firstedit",
                "order=descending",
                "resultsheader=共{{NUMBEROFARTICLES}}个页面\n",
                "cacheperiod=3600",
                "allowcachedresults=true"));

        assertTrue(result.contains("共42个页面"), result);
        assertTrue(result.contains("* [[页面甲]]"), result);
        assertTrue(result.contains("* [[页面乙]]"), result);
        assertEquals("", store.query.namespace());
        assertEquals(8, store.query.limit());
        assertEquals("firstedit", store.query.orderMethod());
        assertTrue(store.query.descending());
    }

    @Test
    void rendersNoResultsHeaderAndCategoryFilter() {
        FakeStore store = new FakeStore(List.of());
        DplFunction function = new DplFunction(store);
        String result = function.invoke(call(
                "category=有未完成章节的页面",
                "ordermethod=title",
                "noresultsheader=无"));

        assertEquals("无", result.strip());
        assertEquals(List.of("有未完成章节的页面"), store.query.categories());
        assertEquals("title", store.query.orderMethod());
    }

    @Test
    void convertsDplWildcardsToRegex() {
        assertEquals("^\\QDPL\\E.*$", DplFunction.wildcard("DPL%"));
        assertEquals("^\\QDPL\\E.\\Q1\\E$", DplFunction.wildcard("DPL_1"));
        assertEquals("^\\QDPL[甲]\\E$", DplFunction.wildcard("DPL[甲]"));
    }

    private static PageRow row(String title) {
        LocalDateTime now = LocalDateTime.now();
        return new PageRow(Math.abs(title.hashCode()), title, 1L, now, now);
    }

    private static ParserFunctionCall call(String... args) {
        return new ParserFunctionCall() {
            private final Map<String, Object> store = new LinkedHashMap<>();

            @Override
            public WikiRenderContext context() {
                return new WikiRenderContext() {
                    @Override
                    public String pageTitle() {
                        return "测试页";
                    }

                    @Override
                    public Map<String, Object> store() {
                        return store;
                    }

                    @Override
                    public void setProperty(String name, String value) {
                    }

                    @Override
                    public String property(String name, String defaultValue) {
                        return defaultValue;
                    }

                    @Override
                    public Map<String, String> properties() {
                        return Map.of();
                    }

                    @Override
                    public String deferWikitext(java.util.function.Supplier<String> resolver) {
                        return "";
                    }
                };
            }

            @Override
            public List<String> args() {
                return List.of(args);
            }

            @Override
            public String expand(String wikitext) {
                return wikitext == null ? "" : wikitext.replace("{{NUMBEROFARTICLES}}", "42");
            }
        };
    }

    private static final class FakeStore implements PageSearchStore {
        private final List<PageRow> rows;
        private PageQuery query;

        private FakeStore(List<PageRow> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override
        public List<PageRow> query(PageQuery query) {
            this.query = query;
            return List.copyOf(rows);
        }
    }
}
