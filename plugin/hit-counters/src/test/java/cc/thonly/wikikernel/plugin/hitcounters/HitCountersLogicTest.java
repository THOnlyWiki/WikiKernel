package cc.thonly.wikikernel.plugin.hitcounters;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.PageViewEvent;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PagePropertyValue;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.hitcounters.hook.HitCountersFooterHook;
import cc.thonly.wikikernel.plugin.hitcounters.hook.HitCountersViewHook;
import cc.thonly.wikikernel.plugin.hitcounters.hook.PageViewsMagicWord;
import cc.thonly.wikikernel.plugin.hitcounters.hook.TotalViewsMagicWord;
import cc.thonly.wikikernel.plugin.hitcounters.web.PopularPagesSpecialPage;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HitCounters：浏览计数钩子、魔术字、页脚与「最多浏览」的过滤逻辑。
 */
class HitCountersLogicTest {

    private static final HitCountersConfig DEFAULT = new HitCountersConfig(false, true, false, false, List.of());

    /** 测试用消息源：直接读插件自己 jar 里的中文消息包（验证 i18n/messages.properties 能取到文案）。 */
    private static final MessageSource MESSAGES = new MessageSource() {

        private final Map<String, String> zh = new HitCountersMessages().messages().getOrDefault("zh", Map.of());

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            String value = zh.get(code);
            if (value == null) {
                return defaultMessage == null ? code : defaultMessage;
            }
            return args == null || args.length == 0 ? value : new java.text.MessageFormat(value).format(args);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return getMessage(code, args, null, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return resolvable.getDefaultMessage();
        }
    };

    @Test
    void viewHookCountsAndSkipsBotsAndExemptGroups() {
        FakePropertyStore store = new FakePropertyStore();
        HitCountersViewHook hook = new HitCountersViewHook(
                new HitCountersConfig(false, true, false, false, List.of("免计数")), store);

        hook.pageViewed(new PageViewEvent(1, "页面", null, false, Set.of()));
        hook.pageViewed(new PageViewEvent(1, "页面", null, false, Set.of()));
        assertEquals(2, store.value(1));

        // 机器人不计数
        hook.pageViewed(new PageViewEvent(1, "页面", null, true, Set.of("机器人")));
        assertEquals(2, store.value(1));

        // 豁免组不计数
        hook.pageViewed(new PageViewEvent(1, "页面", null, false, Set.of("免计数")));
        assertEquals(2, store.value(1));

        // 关闭计数
        HitCountersViewHook disabled = new HitCountersViewHook(
                new HitCountersConfig(true, true, false, false, List.of()), store);
        disabled.pageViewed(new PageViewEvent(1, "页面", null, false, Set.of()));
        assertEquals(2, store.value(1));
    }

    @Test
    void magicWordsShowTotalsAndPageViews() {
        FakePropertyStore store = new FakePropertyStore();
        store.set(1, HitCountersPlugin.VIEWS_PROPERTY, "41");
        store.set(2, HitCountersPlugin.VIEWS_PROPERTY, "1");
        FakePageStore pages = new FakePageStore(Map.of(
                "首页", new PageSnapshot(1, "首页", false, true, 100, null)));

        TotalViewsMagicWord total = new TotalViewsMagicWord(DEFAULT, store);
        assertEquals("42", total.value(new FakeContext("首页")));
        PageViewsMagicWord pageViews = new PageViewsMagicWord(DEFAULT, store, pages);
        assertEquals("41", pageViews.value(new FakeContext("首页")));
        assertEquals("", pageViews.value(new FakeContext("不存在的页面")));

        TotalViewsMagicWord disabled = new TotalViewsMagicWord(
                new HitCountersConfig(true, true, false, false, List.of()), store);
        assertEquals("", disabled.value(new FakeContext("首页")));
    }

    @Test
    void footerHookAppendsViewCount() {
        FakePropertyStore store = new FakePropertyStore();
        store.set(1, HitCountersPlugin.VIEWS_PROPERTY, "7");
        FakePageStore pages = new FakePageStore(Map.of(
                "首页", new PageSnapshot(1, "首页", false, true, 100, null)));

        HitCountersFooterHook hook = new HitCountersFooterHook(DEFAULT, store, pages, MESSAGES);
        String html = hook.afterRender("首页", "<p>正文</p>");
        assertTrue(html.contains("本页面已被访问"), html);
        assertTrue(html.contains("7"), html);

        // 没有计数不追加
        store.set(2, HitCountersPlugin.VIEWS_PROPERTY, "0");
        FakePageStore other = new FakePageStore(Map.of(
                "零", new PageSnapshot(2, "零", false, true, 10, null)));
        assertEquals("<p>正文</p>",
                new HitCountersFooterHook(DEFAULT, store, other, MESSAGES).afterRender("零", "<p>正文</p>"));

        // 页脚开关关闭
        HitCountersFooterHook noFooter = new HitCountersFooterHook(
                new HitCountersConfig(false, false, false, false, List.of()), store, pages, MESSAGES);
        assertEquals("<p>正文</p>", noFooter.afterRender("首页", "<p>正文</p>"));
    }

    @Test
    void popularPagesFiltersAndLists() {
        FakePropertyStore store = new FakePropertyStore();
        store.set(1, HitCountersPlugin.VIEWS_PROPERTY, "30");
        store.set(2, HitCountersPlugin.VIEWS_PROPERTY, "50");
        store.set(3, HitCountersPlugin.VIEWS_PROPERTY, "10");
        store.set(4, HitCountersPlugin.VIEWS_PROPERTY, "99");
        FakePageStore pages = new FakePageStore(new LinkedHashMap<>(Map.of(
                // 内容页
                "甲", new PageSnapshot(1, "甲", false, true, 100, null),
                "乙", new PageSnapshot(2, "乙", false, true, 200, null),
                // 重定向：过滤
                "丙", new PageSnapshot(3, "丙", true, true, 10, null),
                // 非内容命名空间：过滤
                "模板:丁", new PageSnapshot(4, "模板:丁", false, false, 10, null))));

        PopularPagesSpecialPage specialPage = new PopularPagesSpecialPage(
                new HitCountersConfig(false, true, true, true, List.of()), store, pages, MESSAGES);
        // 展示名走插件消息包（URL 名仍是 name() 里的中文）
        assertEquals("最多浏览", specialPage.displayName());
        assertEquals("最多浏览", specialPage.name());
        Map<String, Object> model = new LinkedHashMap<>();
        specialPage.populate(new SpecialPageRequest("最多浏览", Map.of()), model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("rows");
        assertEquals(2, rows.size());
        // 按浏览量倒序：乙(50) 在 甲(30) 前
        assertEquals("乙", rows.get(0).get("title"));
        assertEquals("甲", rows.get(1).get("title"));
        assertEquals(Boolean.TRUE, model.get("showLength"));
        assertEquals(Boolean.TRUE, model.get("showPageId"));
        // 全站总量汇总所有页面（含被过滤的重定向 / 非内容页）：30+50+10+99
        assertEquals("189", model.get("totalViewsText").toString().replace(",", ""));
    }

    // ==================== 测试替身 ====================

    private static final class FakePropertyStore implements PagePropertyStore {

        private final Map<Long, Map<String, String>> values = new LinkedHashMap<>();

        long value(long pageId) {
            return HitCountersSupport.parseLong(get(pageId, HitCountersPlugin.VIEWS_PROPERTY).orElse("0"));
        }

        @Override
        public Optional<String> get(long pageId, String name) {
            return Optional.ofNullable(values.getOrDefault(pageId, Map.of()).get(name));
        }

        @Override
        public Map<Long, Map<String, String>> batch(Collection<Long> pageIds, Collection<String> names) {
            Map<Long, Map<String, String>> result = new LinkedHashMap<>();
            for (Long pageId : pageIds) {
                Map<String, String> filtered = new LinkedHashMap<>();
                for (String name : names) {
                    String value = values.getOrDefault(pageId, Map.of()).get(name);
                    if (value != null) {
                        filtered.put(name, value);
                    }
                }
                result.put(pageId, filtered);
            }
            return result;
        }

        @Override
        public Map<Long, Map<String, String>> all(Collection<Long> pageIds) {
            Map<Long, Map<String, String>> result = new LinkedHashMap<>();
            for (Long pageId : pageIds) {
                result.put(pageId, Map.copyOf(values.getOrDefault(pageId, Map.of())));
            }
            return result;
        }

        @Override
        public void set(long pageId, String name, String value) {
            values.computeIfAbsent(pageId, key -> new LinkedHashMap<>()).put(name, value);
        }

        @Override
        public void remove(long pageId, String name) {
            values.getOrDefault(pageId, Map.of()).remove(name);
        }

        @Override
        public void removeAll(long pageId) {
            values.remove(pageId);
        }

        @Override
        public void replaceRendered(long pageId, Map<String, String> rendered) {
            values.put(pageId, new LinkedHashMap<>(rendered));
        }

        @Override
        public void registerDurable(String name) {
        }

        @Override
        public long addToInt(long pageId, String name, long delta) {
            long next = value(pageId) + delta;
            set(pageId, name, String.valueOf(next));
            return next;
        }

        @Override
        public long sumInt(String name) {
            return values.values().stream()
                    .mapToLong(row -> HitCountersSupport.parseLong(row.getOrDefault(name, "0")))
                    .sum();
        }

        @Override
        public List<PagePropertyValue> listByIntDesc(String name, int limit) {
            List<PagePropertyValue> result = values.entrySet().stream()
                    .filter(entry -> entry.getValue().containsKey(name))
                    .map(entry -> new PagePropertyValue(entry.getKey(),
                            HitCountersSupport.parseLong(entry.getValue().get(name))))
                    .sorted((left, right) -> Long.compare(right.value(), left.value()))
                    .toList();
            return limit > 0 && result.size() > limit ? result.subList(0, limit) : result;
        }
    }

    private static final class FakePageStore implements WikiPageStore {

        private final Map<String, PageSnapshot> byTitle = new LinkedHashMap<>();
        private final Map<Long, PageSnapshot> byId = new LinkedHashMap<>();

        FakePageStore(Map<String, PageSnapshot> pages) {
            for (PageSnapshot page : pages.values()) {
                byId.put(page.id(), page);
            }
            byTitle.putAll(pages);
        }

        @Override
        public Optional<PageSnapshot> byId(long pageId) {
            return Optional.ofNullable(byId.get(pageId));
        }

        @Override
        public Optional<PageSnapshot> byTitle(String title) {
            return Optional.ofNullable(byTitle.get(title));
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.empty();
        }

        @Override
        public List<PageSnapshot> contentPages() {
            return byId.values().stream().filter(PageSnapshot::contentPage).toList();
        }
    }

    private static final class FakeContext implements WikiRenderContext {

        private final String pageTitle;

        FakeContext(String pageTitle) {
            this.pageTitle = pageTitle;
        }

        @Override
        public String pageTitle() {
            return pageTitle;
        }

        @Override
        public Map<String, Object> store() {
            return new LinkedHashMap<>();
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
        public String deferWikitext(Supplier<String> resolver) {
            return resolver.get();
        }
    }
}
