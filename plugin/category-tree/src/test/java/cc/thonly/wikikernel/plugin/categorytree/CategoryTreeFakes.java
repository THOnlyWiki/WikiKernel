package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.common.extension.ActionApiRequest;
import cc.thonly.wikikernel.common.extension.ApiPageRef;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.CategoryCounts;
import cc.thonly.wikikernel.common.service.CategoryMember;
import cc.thonly.wikikernel.common.service.CategoryStore;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeTag;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 单元测试用的测试替身（不依赖 Spring）。 */
public final class CategoryTreeFakes {

    private CategoryTreeFakes() {
    }

    /**
     * 演示 wiki：
     * 分类:甲（子分类 子一 / 子二，页面 页面一，文件 图.png），
     * 分类:子一（子分类 孙一，页面 页面二），分类:子二（空），分类:孙一（空）。
     */
    public static FakeCategoryStore demoStore() {
        FakeCategoryStore store = new FakeCategoryStore();
        store.put("分类:甲",
                member(1, "分类:子一", "", 14),
                member(2, "分类:子二", "", 14),
                member(3, "页面一", "甲页面", 0),
                member(4, "文件:图.png", "", 6));
        store.put("分类:子一",
                member(7, "分类:孙一", "", 14),
                member(5, "页面二", "", 0));
        store.put("分类:子二");
        store.put("分类:孙一");
        store.parents(100, "分类:父一", "分类:父二");
        return store;
    }

    public static FakePageStore demoPages() {
        FakePageStore pages = new FakePageStore();
        pages.add(100, "分类:甲");
        pages.add(101, "分类:子一");
        pages.add(102, "分类:子二");
        pages.add(103, "页面一");
        pages.add(104, "页面二");
        pages.add(105, "文件:图.png");
        pages.add(106, "条目:甲");
        pages.add(107, "分类:孙一");
        pages.add(108, "分类:父一");
        pages.add(109, "分类:父二");
        return pages;
    }

    public static CategoryTreeRenderer renderer(FakeCategoryStore store, FakePageStore pages) {
        return new CategoryTreeRenderer(store, pages, CategoryTreeConfig.defaults(),
                new CategoryTreeMessages(Locale.SIMPLIFIED_CHINESE));
    }

    public static CategoryMember member(long pageId, String title, String sortKey, int namespaceId) {
        return new CategoryMember(pageId, title, sortKey, namespaceId);
    }

    /** 内存版分类索引：与 core 的 {@code CategoryStoreImpl} 语义一致（members 按排序键 → 页面 ID 排序）。 */
    public static final class FakeCategoryStore implements CategoryStore {

        private final Map<String, List<CategoryMember>> members = new LinkedHashMap<>();
        private final Map<Long, List<String>> parents = new LinkedHashMap<>();

        /** members() 收到的 limit 参数（按调用顺序记录，用于断言 max-children 的传递方式）。 */
        public final List<Integer> limits = new ArrayList<>();

        void put(String categoryTitle, CategoryMember... values) {
            this.members.put(categoryTitle, List.of(values));
        }

        void parents(long pageId, String... categories) {
            this.parents.put(pageId, List.of(categories));
        }

        @Override
        public List<CategoryMember> members(String categoryTitle, int limit) {
            this.limits.add(limit);
            List<CategoryMember> all = this.members.getOrDefault(canonical(categoryTitle), List.of());
            if (limit > 0 && all.size() > limit) {
                return List.copyOf(all.subList(0, limit));
            }
            return List.copyOf(all);
        }

        @Override
        public List<String> categoriesOf(long pageId) {
            return this.parents.getOrDefault(pageId, List.of());
        }

        @Override
        public CategoryCounts counts(String categoryTitle) {
            List<CategoryMember> all = members(categoryTitle, 0);
            int subcategories = 0;
            int pagesCount = 0;
            int files = 0;
            for (CategoryMember member : all) {
                if (member.subcategory()) {
                    subcategories++;
                } else if (member.file()) {
                    files++;
                } else if (member.contentPage()) {
                    pagesCount++;
                }
            }
            return new CategoryCounts(subcategories, pagesCount, files, all.size());
        }

        private static String canonical(String title) {
            String value = title == null ? "" : title.trim();
            if (value.startsWith("分类:") || value.startsWith("Category:")) {
                return "分类:" + value.substring(value.indexOf(':') + 1);
            }
            return "分类:" + value;
        }
    }

    /** 内存版页面存储。 */
    public static final class FakePageStore implements WikiPageStore {

        private final Map<String, PageSnapshot> byTitle = new LinkedHashMap<>();
        private final Map<Long, PageSnapshot> byId = new LinkedHashMap<>();

        void add(long id, String title) {
            PageSnapshot snapshot = new PageSnapshot(id, title, false, !title.contains(":"), 10, null);
            this.byTitle.put(title, snapshot);
            this.byId.put(id, snapshot);
        }

        @Override
        public Optional<PageSnapshot> byId(long pageId) {
            return Optional.ofNullable(this.byId.get(pageId));
        }

        @Override
        public Optional<PageSnapshot> byTitle(String title) {
            return Optional.ofNullable(this.byTitle.get(title));
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.empty();
        }

        @Override
        public List<PageSnapshot> contentPages() {
            return List.copyOf(this.byId.values());
        }
    }

    /** 内存版渲染上下文。 */
    public static final class FakeRenderContext implements WikiRenderContext {

        private final String pageTitle;
        private final Map<String, Object> store = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        public FakeRenderContext(String pageTitle) {
            this.pageTitle = pageTitle;
        }

        public String property(String name) {
            return this.properties.get(name);
        }

        @Override
        public String pageTitle() {
            return this.pageTitle;
        }

        @Override
        public Map<String, Object> store() {
            return this.store;
        }

        @Override
        public void setProperty(String name, String value) {
            this.properties.put(name, value);
        }

        @Override
        public String property(String name, String defaultValue) {
            return this.properties.getOrDefault(name, defaultValue);
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(this.properties);
        }

        @Override
        public String deferWikitext(Supplier<String> resolver) {
            return resolver.get();
        }
    }

    /** 标签调用替身。 */
    public static final class FakeTagCall implements ParserTagCall {

        private final WikiRenderContext context;
        private final Map<String, String> attributes;
        private final String body;

        public FakeTagCall(WikiRenderContext context, Map<String, String> attributes, String body) {
            this.context = context;
            this.attributes = Map.copyOf(attributes);
            this.body = body;
        }

        @Override
        public WikiRenderContext context() {
            return this.context;
        }

        @Override
        public String tag() {
            return CategoryTreeTag.TAG_NAME;
        }

        @Override
        public Map<String, String> attributes() {
            return this.attributes;
        }

        @Override
        public String body() {
            return this.body;
        }

        @Override
        public String expand(String wikitext) {
            return wikitext;
        }
    }

    /** 解析器函数调用替身：{@code expand} 走一个可注入的映射（模拟模板参数展开）。 */
    public static final class FakeFunctionCall implements ParserFunctionCall {

        private final WikiRenderContext context;
        private final List<String> args;
        private final Function<String, String> expansion;

        public FakeFunctionCall(WikiRenderContext context, List<String> args) {
            this(context, args, Function.identity());
        }

        public FakeFunctionCall(WikiRenderContext context, List<String> args, Function<String, String> expansion) {
            this.context = context;
            this.args = List.copyOf(args);
            this.expansion = expansion;
        }

        @Override
        public WikiRenderContext context() {
            return this.context;
        }

        @Override
        public List<String> args() {
            return this.args;
        }

        @Override
        public String expand(String wikitext) {
            String expanded = this.expansion.apply(wikitext);
            return expanded == null ? wikitext : expanded;
        }
    }

    /** Action API 请求替身（本插件只用到 params）。 */
    public static final class FakeApiRequest implements ActionApiRequest {

        private final Map<String, String> params;

        public FakeApiRequest(Map<String, String> params) {
            this.params = Map.copyOf(params);
        }

        @Override
        public Map<String, String> params() {
            return this.params;
        }

        @Override
        public int formatVersion() {
            return 2;
        }

        @Override
        public String username() {
            return "";
        }

        @Override
        public boolean loggedIn() {
            return false;
        }

        @Override
        public Set<String> groups() {
            return Set.of();
        }

        @Override
        public List<ApiPageRef> resolvePages(String titles) {
            return List.of();
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds, Collection<String> names) {
            return Map.of();
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds) {
            return Map.of();
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.empty();
        }
    }

    /** 简单的模板参数展开替身：把 {@code {{{名}}}} 全部替换成对应值。 */
    public static Function<String, String> frame(Map<String, String> values) {
        return text -> {
            String result = text == null ? "" : text;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        };
    }

    /** 断言 HTML 里包含某个片段。 */
    public static void assertContains(String expected, String html) {
        assertTrue(html.contains(expected), "期望包含：" + expected + "\n实际：" + html);
    }

    /** 断言 HTML 里不包含某个片段。 */
    public static void assertNotContains(String unexpected, String html) {
        assertTrue(!html.contains(unexpected), "期望不包含：" + unexpected + "\n实际：" + html);
    }

    /** 断言出现次数。 */
    public static void assertCount(int expected, String needle, String html) {
        int count = 0;
        int index = html.indexOf(needle);
        while (index >= 0) {
            count++;
            index = html.indexOf(needle, index + needle.length());
        }
        assertEquals(expected, count, "「" + needle + "」出现次数不符：\n" + html);
    }

    /** 简单的列表收集工具（保持顺序）。 */
    public static <T> List<T> list(T... values) {
        return new ArrayList<>(List.of(values));
    }
}
