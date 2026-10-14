package cc.thonly.wikikernel.plugin.templatedata.hook;

import cc.thonly.wikikernel.common.extension.ActionApiException;
import cc.thonly.wikikernel.common.extension.ActionApiRequest;
import cc.thonly.wikikernel.common.extension.ApiPageRef;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataConfig;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.service.TemplateDataBlob;
import cc.thonly.wikikernel.plugin.templatedata.service.Values;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiTemplateData}：API 输出结构、缺失标题、原始参数提取与损坏数据。
 * 用假的 {@link ActionApiRequest} 构造页面 / 属性 / 正文，不需要 Spring。
 */
class ApiTemplateDataTest {

    private static final TemplateDataConfig CONFIG = new TemplateDataConfig("zh", false);
    private static final TemplateDataMessages ZH = TemplateDataMessages.of("zh");

    @Test
    void returnsNormalizedDataForPagesWithTemplateData() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:问候"));
        request.addPage(1, "模板:问候");
        request.addProperty(1, TemplateDataBlob.PROPERTY_NAME,
                TemplateDataBlob.parse("{\"description\":\"打招呼\",\"params\":{\"1\":{}}}", "zh").getJsonForDatabase());

        Map<String, Object> result = handle(request);
        Map<String, Object> pages = Values.asMap(result.get("pages"));
        assertEquals(List.of("1"), new ArrayList<>(pages.keySet()));

        Map<String, Object> page = Values.asMap(pages.get("1"));
        assertEquals("模板:问候", page.get("title"));
        assertEquals(Map.of("zh", "打招呼"), page.get("description"));
        Map<String, Object> params = Values.asMap(page.get("params"));
        assertEquals(List.of("1"), new ArrayList<>(params.keySet()));
        assertEquals("unknown", Values.asMap(params.get("1")).get("type"));
        assertEquals(List.of(), page.get("sets"));
        assertEquals(null, page.get("format"));
        assertEquals(Map.of(), page.get("maps"));
    }

    @Test
    void pagesWithoutTemplateDataAreSkippedByDefault() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:无数据"));
        request.addPage(2, "模板:无数据");
        request.addContent(2, "{{{foo|}}}{{{bar|}}}");

        Map<String, Object> result = handle(request);
        assertTrue(Values.asMap(result.get("pages")).isEmpty(), () -> "默认不返回没有数据的页面：" + result);
    }

    @Test
    void includeMissingTitlesReturnsMissingAndNotemplatedataPages() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:有数据|模板:无数据|模板:不存在",
                "includeMissingTitles", "1"));
        request.addPage(1, "模板:有数据");
        request.addProperty(1, TemplateDataBlob.PROPERTY_NAME,
                TemplateDataBlob.parse("{\"params\":{}}", "zh").getJsonForDatabase());
        request.addPage(2, "模板:无数据");
        request.addContent(2, """
                <!-- {{{ignored|}}} -->
                {{{foo|默认值}}}{{{ foo |}}}{{{1|}}}{{{bar<nowiki>{{{nowikiParam|}}}</nowiki>}}}{{{baz}}}
                """);
        request.addMissing("模板:不存在");

        Map<String, Object> pages = Values.asMap(handle(request).get("pages"));
        // 有数据的页面：正常返回；缺失页面用负数 id；没有数据的页面带 notemplatedata
        assertFalse(Values.asMap(pages.get("1")).containsKey("notemplatedata"));
        assertEquals("模板:不存在", Values.asMap(pages.get("-1")).get("title"));
        assertEquals(Boolean.TRUE, Values.asMap(pages.get("-1")).get("missing"));

        Map<String, Object> noData = Values.asMap(pages.get("2"));
        assertEquals("模板:无数据", noData.get("title"));
        assertEquals(Boolean.TRUE, noData.get("notemplatedata"));
        // 提取到的原始参数：注释 / nowiki 里的忽略、小写化去重、保留首次出现的写法
        assertEquals(List.of("foo", "1", "bar", "baz"), new ArrayList<>(Values.asMap(noData.get("params")).keySet()));
    }

    @Test
    void deprecatedAliasWorksAndFalseDisablesIt() {
        FakeRequest alias = new FakeRequest(Map.of("titles", "模板:不存在", "doNotIgnoreMissingTitles", "1"));
        alias.addMissing("模板:不存在");
        assertTrue(Values.asMap(handle(alias).get("pages")).containsKey("-1"));

        FakeRequest disabled = new FakeRequest(Map.of("titles", "模板:不存在", "includeMissingTitles", "false"));
        disabled.addMissing("模板:不存在");
        assertTrue(Values.asMap(handle(disabled).get("pages")).isEmpty());
    }

    @Test
    void langResolvesInterfaceTextToASingleString() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:问候", "lang", "en"));
        request.addPage(1, "模板:问候");
        request.addProperty(1, TemplateDataBlob.PROPERTY_NAME, TemplateDataBlob.parse("""
                {"description":{"zh":"你好","en":"Hello"},"params":{"1":{"label":{"en":"First"}}}}
                """, "zh").getJsonForDatabase());

        Map<String, Object> page = Values.asMap(Values.asMap(handle(request).get("pages")).get("1"));
        assertEquals("Hello", page.get("description"));
        assertEquals("First", Values.asMap(Values.asMap(page.get("params")).get("1")).get("label"));
    }

    @Test
    void corruptPagePropertyRaisesActionApiException() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:坏数据"));
        request.addPage(7, "模板:坏数据");
        request.addProperty(7, TemplateDataBlob.PROPERTY_NAME, "{\"params\":[]}");

        ActionApiException exception = assertThrows(ActionApiException.class, () -> handle(request));
        assertEquals("templatedata-corrupt", exception.code());
        assertTrue(exception.info().contains("页面#7"), exception.info());
        assertTrue(exception.info().contains("属性“params”的类型应为“object”。"), exception.info());
    }

    @Test
    void noTitlesGivesEmptyPagesObject() {
        Map<String, Object> result = handle(new FakeRequest(Map.of()));
        assertTrue(Values.asMap(result.get("pages")).isEmpty());
    }

    @Test
    void duplicateTitlesAreReturnedOnce() {
        FakeRequest request = new FakeRequest(Map.of("titles", "模板:问候|模板:问候"));
        request.addPage(1, "模板:问候");
        request.addProperty(1, TemplateDataBlob.PROPERTY_NAME,
                TemplateDataBlob.parse("{\"params\":{}}", "zh").getJsonForDatabase());
        Map<String, Object> pages = Values.asMap(handle(request).get("pages"));
        assertEquals(List.of("1"), new ArrayList<>(pages.keySet()));
    }

    @Test
    void rawParamsFollowTheOriginalApiRules() {
        Map<String, Object> params = ApiTemplateData.rawParams("""
                {{{foo|}}}
                {{{Foo}}}
                {{{ foo_bar |}}}
                {{{1|}}}
                {{{2|}}}
                {{{#tag|}}}
                {{{a=b|}}}
                {{{|}}}
                {{{ 空格 名称 |}}}
                """);
        // 归一化（-/_/空格 → 单空格、去首尾、ASCII 小写）后去重，键保留第一次出现的写法
        assertEquals(List.of("foo", "foo_bar", "1", "2", "空格 名称"), new ArrayList<>(params.keySet()));
        for (Object value : params.values()) {
            assertEquals(Map.of(), value);
        }
    }

    @Test
    void rawParamsIgnoreCommentsAndEscapedTags() {
        Map<String, Object> params = ApiTemplateData.rawParams(
                "{{{keep}}}<!-- {{{comment}}} --><nowiki>{{{nowikiParam}}}</nowiki><pre>{{{preParam}}}</pre>");
        assertEquals(List.of("keep"), new ArrayList<>(params.keySet()));
    }

    @Test
    void pluginMetadataIsExposed() {
        assertEquals("templatedata", new ApiTemplateData(CONFIG).action());
    }

    private static Map<String, Object> handle(ActionApiRequest request) {
        return Values.asMap(new ApiTemplateData(CONFIG, ZH).handle(request));
    }

    /** 假请求：页面表 / 属性表 / 正文表都由测试构造。 */
    private static final class FakeRequest implements ActionApiRequest {

        private final Map<String, String> params;
        private final Map<String, ApiPageRef> pages = new LinkedHashMap<>();
        private final Map<Long, Map<String, String>> properties = new LinkedHashMap<>();
        private final Map<Long, String> contents = new LinkedHashMap<>();

        private FakeRequest(Map<String, String> params) {
            this.params = new LinkedHashMap<>(params);
        }

        private void addPage(long id, String title) {
            pages.put(title, new ApiPageRef(id, title, false, null));
        }

        private void addMissing(String title) {
            pages.put(title, new ApiPageRef(0, title, true, null));
        }

        private void addProperty(long pageId, String name, String value) {
            properties.computeIfAbsent(pageId, key -> new LinkedHashMap<>()).put(name, value);
        }

        private void addContent(long pageId, String content) {
            contents.put(pageId, content);
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        public int formatVersion() {
            return 1;
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
            List<ApiPageRef> refs = new ArrayList<>();
            if (titles == null || titles.isBlank()) {
                return refs;
            }
            for (String title : titles.split("\\|")) {
                String normalized = title.trim();
                refs.add(pages.getOrDefault(normalized, new ApiPageRef(0, normalized, true, null)));
            }
            return refs;
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds, Collection<String> names) {
            Map<Long, Map<String, String>> result = new LinkedHashMap<>();
            for (Long pageId : pageIds) {
                Map<String, String> all = properties.get(pageId);
                if (all == null) {
                    continue;
                }
                Map<String, String> filtered = new LinkedHashMap<>();
                for (String name : names) {
                    if (all.containsKey(name)) {
                        filtered.put(name, all.get(name));
                    }
                }
                if (!filtered.isEmpty()) {
                    result.put(pageId, filtered);
                }
            }
            return result;
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds) {
            Map<Long, Map<String, String>> result = new LinkedHashMap<>();
            for (Long pageId : pageIds) {
                if (properties.containsKey(pageId)) {
                    result.put(pageId, properties.get(pageId));
                }
            }
            return result;
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.ofNullable(contents.get(pageId));
        }
    }
}
