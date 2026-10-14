package cc.thonly.wikikernel.plugin.templatedata.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateDataBlob}：解析 / 校验 / 规范化 / 语言解析。
 * 用例从原版 {@code tests/phpunit/TemplateDataBlobTest.php} 里挑选代表性的（校验错误、
 * 默认值补齐、inherits、接口文本、paramOrder、sets、maps、format、getDataInLanguage）。
 */
class TemplateDataBlobTest {

    private static final String ZH = "zh";

    // ==================== 合法 JSON 与规范化 ====================

    @Test
    void minimalValidBlobGetsDefaults() {
        assertNormalized("""
                {"params":{}}
                """, """
                {"params":{},"description":null,"sets":[],"maps":{},"format":null}
                """);
    }

    @Test
    void optionalParameterPropertiesAreAdded() {
        assertNormalized("""
                {"params":{"foo":{}}}
                """, """
                {
                  "description": null,
                  "params": {
                    "foo": {
                      "label": null,
                      "description": null,
                      "default": null,
                      "example": null,
                      "required": false,
                      "suggested": false,
                      "suggestedvalues": [],
                      "deprecated": false,
                      "aliases": [],
                      "type": "unknown",
                      "autovalue": null
                    }
                  },
                  "sets": [],
                  "format": null,
                  "maps": {}
                }
                """);
    }

    @Test
    void deprecatedStringTypesAreMapped() {
        Map<String, Object> data = parseOk("""
                {"params":{"comment":{"type":"string/line"},"page":{"type":"string/wiki-page-name"}}}
                """);
        assertEquals("line", param(data, "comment").get("type"));
        assertEquals("wiki-page-name", param(data, "page").get("type"));
    }

    @Test
    void interfaceTextStringsBecomeLanguageObjects() {
        Map<String, Object> data = parseOk("""
                {
                  "description": "User badge MediaWiki developers.",
                  "params": {
                    "nickname": {
                      "label": null,
                      "description": "User name of user who owns the badge",
                      "default": "Base page name of the host page",
                      "example": null,
                      "required": false,
                      "suggested": true,
                      "aliases": [ 1 ]
                    }
                  }
                }
                """);
        assertEquals(Map.of("zh", "User badge MediaWiki developers."), data.get("description"));
        Map<String, Object> nickname = param(data, "nickname");
        assertEquals(Map.of("zh", "User name of user who owns the badge"), nickname.get("description"));
        assertEquals(Map.of("zh", "Base page name of the host page"), nickname.get("default"));
        assertNull(nickname.get("example"));
        assertEquals(List.of("1"), nickname.get("aliases"));
        assertEquals(Boolean.TRUE, nickname.get("suggested"));
        assertEquals(Boolean.FALSE, nickname.get("required"));
    }

    @Test
    void multiLanguageInterfaceTextIsKept() {
        Map<String, Object> data = parseOk("""
                {"description":{"zh":"中文","en":"English"},"params":{}}
                """);
        assertEquals(Map.of("zh", "中文", "en", "English"), data.get("description"));
    }

    @Test
    void inheritsCopiesParentPropertiesButKeepsOverrides() {
        Map<String, Object> data = parseOk("""
                {
                  "description": "Document the documenter.",
                  "params": {
                    "1d": {
                      "description": "Description of the template parameter",
                      "required": true,
                      "default": "example"
                    },
                    "2d": {
                      "inherits": "1d",
                      "default": "overridden"
                    }
                  }
                }
                """);
        Map<String, Object> first = param(data, "1d");
        Map<String, Object> second = param(data, "2d");
        assertFalse(second.containsKey("inherits"));
        assertEquals(first.get("description"), second.get("description"));
        assertEquals(Boolean.TRUE, second.get("required"));
        assertEquals(Map.of("zh", "overridden"), second.get("default"));
    }

    @Test
    void parameterAttributesPreserveInformation() {
        Map<String, Object> data = parseOk("""
                {
                  "params": {
                    "bar": {
                      "label": "Bar label",
                      "description": "Bar description",
                      "default": "Baz",
                      "example": "Foo bar baz",
                      "autovalue": "{{SomeTemplate}}",
                      "required": true,
                      "suggested": false,
                      "suggestedvalues": [ "baz", "boo" ],
                      "deprecated": false,
                      "aliases": [ "foo", "baz" ],
                      "type": "line"
                    }
                  }
                }
                """);
        Map<String, Object> bar = param(data, "bar");
        assertEquals(Map.of("zh", "Bar label"), bar.get("label"));
        assertEquals("{{SomeTemplate}}", bar.get("autovalue"));
        assertEquals(Boolean.TRUE, bar.get("required"));
        assertEquals(List.of("baz", "boo"), bar.get("suggestedvalues"));
        assertEquals(List.of("foo", "baz"), bar.get("aliases"));
        assertEquals("line", bar.get("type"));
    }

    @Test
    void setsAreValidatedAndNormalized() {
        Map<String, Object> data = parseOk("""
                {
                  "params": { "foo": {}, "bar": {}, "quux": {} },
                  "sets": [
                    { "label": "Foo with Quux", "params": ["foo", "quux"] },
                    { "label": "Bar with Quux", "params": ["bar", "quux"] }
                  ]
                }
                """);
        List<Object> sets = Values.asList(data.get("sets"));
        assertEquals(2, sets.size());
        assertEquals(Map.of("zh", "Foo with Quux"), Values.asMap(sets.get(0)).get("label"));
        assertEquals(List.of("bar", "quux"), Values.asMap(sets.get(1)).get("params"));
    }

    @Test
    void mapsAreKeptAndValidated() {
        Map<String, Object> data = parseOk("""
                {
                  "params": { "foo": {}, "bar": {} },
                  "maps": {
                    "application": {
                      "things": [ "foo", ["bar"] ]
                    }
                  }
                }
                """);
        Map<String, Object> maps = Values.asMap(data.get("maps"));
        assertTrue(maps.containsKey("application"));
    }

    @Test
    void formatsAreValidated() {
        assertEquals("inline", parseOk("{\"params\":{},\"format\":\"inline\"}").get("format"));
        assertEquals("block", parseOk("{\"params\":{},\"format\":\"block\"}").get("format"));
        assertEquals("{{_ |\n ___ = _}}",
                parseOk("{\"params\":{},\"format\":\"{{_ |\\n ___ = _}}\"}").get("format"));
        assertError("{\"params\":{},\"format\":\"\"}", "templatedata-invalid-format", "format");
        assertError("{\"params\":{},\"format\":\"meshuggah format\"}", "templatedata-invalid-format", "format");
    }

    /** paramOrder 保持用户给定的顺序（原版规范化不会生成 paramOrder）。 */
    @Test
    void customParamOrderIsPreserved() {
        Map<String, Object> data = parseOk("""
                {
                  "params": { "foo": {}, "bar": {}, "baz": {} },
                  "paramOrder": ["baz", "foo", "bar"]
                }
                """);
        assertEquals(List.of("baz", "foo", "bar"), data.get("paramOrder"));
    }

    /** 原版没有的行为：只有打开开关时才补 paramOrder（README 里说明）。 */
    @Test
    void paramOrderIsGeneratedOnlyWhenEnabled() {
        TemplateDataBlob without = TemplateDataBlob.parse(
                "{\"params\":{\"foo\":{},\"bar\":{}}}", ZH);
        assertFalse(without.getData().containsKey("paramOrder"));

        TemplateDataBlob with = TemplateDataBlob.parse(
                "{\"params\":{\"foo\":{},\"bar\":{}}}", ZH, true);
        assertEquals(List.of("foo", "bar"), with.getData().get("paramOrder"));
    }

    @Test
    void normalizedJsonRoundTrips() {
        String json = """
                {
                  "description": "描述",
                  "params": { "1": { "type": "string/line", "required": true } },
                  "sets": [ { "label": "组", "params": ["1"] } ],
                  "format": "block"
                }
                """;
        TemplateDataBlob blob = TemplateDataBlob.parse(json, ZH);
        assertTrue(blob.status().isOk());

        TemplateDataBlob again = TemplateDataBlob.parse(blob.getJsonForDatabase(), ZH);
        assertTrue(again.status().isOk());
        assertEquals(blob.getJsonForDatabase(), again.getJsonForDatabase());
    }

    @Test
    void getDataReturnsDeepCopy() {
        TemplateDataBlob blob = TemplateDataBlob.parse("{\"params\":{\"foo\":{}}}", ZH);
        Map<String, Object> first = blob.getData();
        assertNotSame(first, blob.getData());
        Values.asMap(Values.asMap(first.get("params")).get("foo")).put("type", "number");
        assertEquals("unknown", param(blob.getData(), "foo").get("type"));
    }

    // ==================== 校验错误 ====================

    @Test
    void malformedJsonIsRejected() {
        assertError("{", "templatedata-invalid-parse");
        assertError("", "templatedata-invalid-parse");
        assertError("{\"params\":{}} trailing", "templatedata-invalid-parse");
    }

    @Test
    void rootMustBeAnObjectWithOnlyKnownKeys() {
        assertError("[]", "templatedata-invalid-type", "templatedata", "object");
        assertError("\"x\"", "templatedata-invalid-type", "templatedata", "object");
        assertError("{}", "templatedata-invalid-missing", "params", "object");
        assertError("{\"params\":{},\"foo\":\"bar\"}", "templatedata-invalid-unknown", "foo");
        assertError("{\"description\":[],\"params\":{}}", "templatedata-invalid-type", "description", "string|object");
        assertError("{\"params\":[]}", "templatedata-invalid-type", "params", "object");
    }

    @Test
    void parameterNamesAndValuesAreValidated() {
        assertError("{\"params\":{\"\":{}}}", "templatedata-invalid-unnamed-parameter");
        assertError("{\"params\":{\"  \":{}}}", "templatedata-invalid-unnamed-parameter");
        assertError("{\"params\":{\"a\":[]}}", "templatedata-invalid-type", "params.a", "object");
        assertError("{\"params\":{\"a\":{\"foo\":\"\"}}}", "templatedata-invalid-unknown", "params.a.foo");
        assertError("{\"params\":{\"b\":{\"inherits\":\"a\"}}}", "templatedata-invalid-missing", "params.a");
    }

    @Test
    void parameterTypesAreValidated() {
        assertError("{\"params\":{\"a\":{\"label\":[]}}}", "templatedata-invalid-type", "params.a.label", "string|object");
        assertError("{\"params\":{\"a\":{\"required\":\"\"}}}", "templatedata-invalid-type", "params.a.required", "boolean");
        assertError("{\"params\":{\"a\":{\"suggested\":\"\"}}}", "templatedata-invalid-type", "params.a.suggested", "boolean");
        assertError("{\"params\":{\"a\":{\"description\":[]}}}", "templatedata-invalid-type", "params.a.description", "string|object");
        assertError("{\"params\":{\"a\":{\"example\":[]}}}", "templatedata-invalid-type", "params.a.example", "string|object");
        assertError("{\"params\":{\"a\":{\"default\":[]}}}", "templatedata-invalid-type", "params.a.default", "string|object");
        assertError("{\"params\":{\"a\":{\"deprecated\":[]}}}", "templatedata-invalid-type", "params.a.deprecated", "boolean|string");
        assertError("{\"params\":{\"a\":{\"aliases\":\"\"}}}", "templatedata-invalid-type", "params.a.aliases", "array");
        assertError("{\"params\":{\"a\":{\"aliases\":[\"1\",2,{}]}}}", "templatedata-invalid-type", "params.a.aliases[2]", "int|string");
        assertError("{\"params\":{\"a\":{\"autovalue\":[]}}}", "templatedata-invalid-type", "params.a.autovalue", "string");
        assertError("{\"params\":{\"a\":{\"type\":[]}}}", "templatedata-invalid-type", "params.a.type", "string");
        assertError("{\"params\":{\"a\":{\"type\":\"\"}}}", "templatedata-invalid-value", "params.a.type");
        assertError("{\"params\":{\"a\":{\"type\":\"string/wiki-template-name\"}}}", "templatedata-invalid-value", "params.a.type");
        assertError("{\"params\":{\"a\":{\"suggestedvalues\":\"\"}}}", "templatedata-invalid-type", "params.a.suggestedvalues", "array");
        assertError("{\"params\":{\"a\":{\"suggestedvalues\":[{}]}}}", "templatedata-invalid-type", "params.a.suggestedvalues[0]", "string");
    }

    @Test
    void requiredAndDeprecatedCanBeCombinedButMustBeBooleans() {
        // required 与 deprecated 同时为 true 是合法数据（原版也不禁止，没有「冲突」规则）
        Map<String, Object> data = parseOk("""
                {"params":{"a":{"required":true,"deprecated":true}}}
                """);
        assertEquals(Boolean.TRUE, param(data, "a").get("required"));
        assertEquals(Boolean.TRUE, param(data, "a").get("deprecated"));

        // deprecated 也可以是说明字符串
        Map<String, Object> text = parseOk("""
                {"params":{"a":{"deprecated":"改用 b 代替"}}}
                """);
        assertEquals("改用 b 代替", param(text, "a").get("deprecated"));

        assertError("{\"params\":{\"a\":{\"required\":\"true\"}}}", "templatedata-invalid-type", "params.a.required", "boolean");
        assertError("{\"params\":{\"a\":{\"deprecated\":1}}}", "templatedata-invalid-type", "params.a.deprecated", "boolean|string");
    }

    @Test
    void setsAreValidated() {
        assertError("{\"params\":{},\"sets\":{}}", "templatedata-invalid-type", "sets", "array");
        assertError("{\"params\":{},\"sets\":[[]]}", "templatedata-invalid-value", "sets.0");
        assertError("{\"params\":{},\"sets\":[{\"label\":\"Example\"}]}", "templatedata-invalid-missing", "sets.0.params", "array");
        assertError("{\"params\":{},\"sets\":[{\"label\":\"\",\"params\":{}}]}", "templatedata-invalid-type", "sets.0.params", "array");
        assertError("{\"params\":{},\"sets\":[{\"label\":\"\",\"params\":[]}]}", "templatedata-invalid-empty-array", "sets.0.params");
        assertError("{\"params\":{\"foo\":{}},\"sets\":[{\"params\":[\"foo\"]}]}", "templatedata-invalid-missing", "sets.0.label", "string|object");
        assertError("{\"params\":{},\"sets\":[{\"label\":[]}]}", "templatedata-invalid-type", "sets.0.label", "string|object");
        assertError("{\"params\":{\"foo\":{},\"bar\":{}},\"sets\":[{\"label\":\"x\",\"params\":[\"foo\",\"quux\"]}]}",
                "templatedata-invalid-value", "sets.0.params[1]");
    }

    @Test
    void mapsAreValidated() {
        assertError("{\"params\":{},\"maps\":[]}", "templatedata-invalid-type", "maps", "object");
        assertError("{\"params\":{},\"maps\":{\"a\":[]}}", "templatedata-invalid-type", "maps.a", "object");
        assertError("{\"params\":{},\"maps\":{\"a\":{\"b\":\"c\"}}}", "templatedata-invalid-param", "c", "maps.a.b");
        assertError("{\"params\":{},\"maps\":{\"a\":{\"b\":[{}]}}}", "templatedata-invalid-type", "maps.a.b[0]", "string|array");
        assertError("{\"params\":{},\"maps\":{\"a\":{\"b\":[\"c\"]}}}", "templatedata-invalid-param", "c", "maps.a.b[0]");
        assertError("""
                {"params":{"foo":{},"bar":{}},"maps":{"application":{"things":["foo",["bar","quux"]]}}}
                """, "templatedata-invalid-param", "quux", "maps.application.things[1][1]");
        assertError("""
                {"params":{"foo":{},"bar":{}},"maps":{"application":{"things":{"appbar":"bar"}}}}
                """, "templatedata-invalid-type", "maps.application.things", "string|array");
        assertError("""
                {"params":{"foo":{},"bar":{}},"maps":{"application":{"things":[[true]]}}}
                """, "templatedata-invalid-type", "maps.application.things[0][0]", "string");
    }

    @Test
    void paramOrderIsValidated() {
        assertError("{\"params\":{},\"paramOrder\":{}}", "templatedata-invalid-type", "paramOrder", "array");
        assertError("""
                {"params":{"foo":{},"bar":{},"baz":{}},"paramOrder":["foo","bar"]}
                """, "templatedata-invalid-missing", "paramOrder[ \"baz\" ]");
        assertError("""
                {"params":{"foo":{},"bar":{},"baz":{}},"paramOrder":["foo","bar","baz","quux"]}
                """, "templatedata-invalid-value", "paramOrder[ \"quux\" ]");
        assertError("""
                {"params":{"foo":{},"bar":{},"baz":{}},"paramOrder":["foo","bar","baz","bar"]}
                """, "templatedata-invalid-duplicate-value", "paramOrder[3]", "paramOrder[1]", "bar");
    }

    @Test
    void failedValidationFallsBackToMinimalBlobAndErrorStatus() {
        TemplateDataBlob blob = TemplateDataBlob.parse("[1,2,3]", ZH);
        assertFalse(blob.status().isOk());
        assertEquals("templatedata-invalid-type", blob.status().code());
        // 原版：非法数据会被替换成最小合法 blob，避免非法数据进库
        assertEquals("{\"params\":{},\"description\":null,\"sets\":[],\"maps\":{},\"format\":null}",
                blob.getJsonForDatabase());
    }

    @Test
    void selfInheritanceIsHarmlessAndDropsTheKey() {
        // {"a":{"inherits":"a"}} 是合法数据（父参数就是自己）：原版不会崩，本移植也一样
        Map<String, Object> data = parseOk("""
                {"params":{"a":{"inherits":"a","label":"标签"}}}
                """);
        Map<String, Object> param = param(data, "a");
        assertFalse(param.containsKey("inherits"));
        assertEquals(Map.of("zh", "标签"), param.get("label"));
        assertEquals("unknown", param.get("type"));
    }

    // ==================== 语言解析 ====================

    @Test
    void getDataInLanguagePicksRequestedLanguage() {
        TemplateDataBlob blob = blobWithDescription("""
                {"de":"German","nl":"Dutch","en":"English","de-formal":"German (formal address)"}
                """);
        assertEquals("German", resolvedDescription(blob, "de"));
        assertEquals("German (formal address)", resolvedDescription(blob, "de-formal"));
        assertEquals("Dutch", resolvedDescription(blob, "nl"));

        // 语言变体没有对应文本时退到主语言
        TemplateDataBlob variant = blobWithDescription("""
                {"de":"German","nl":"Dutch","en":"English"}
                """);
        assertEquals("German", resolvedDescription(variant, "de-formal"));
    }

    @Test
    void getDataInLanguageFallsBackToContentLanguageThenEnglish() {
        // 站点内容语言 nl，请求语言 fr：先退到内容语言，再退到 en
        TemplateDataBlob contentLanguage = blobWithDescription("\"Hi\"", "nl");
        assertEquals("Hi", resolvedDescription(contentLanguage, "fr"));

        TemplateDataBlob dutch = blobWithDescription("""
                {"nl":"Dutch","de":"German"}
                """, "nl");
        assertEquals("Dutch", resolvedDescription(dutch, "fr"));

        TemplateDataBlob spanish = blobWithDescription("""
                {"es":"Spanish","de":"German"}
                """, "nl");
        assertNull(resolvedDescription(spanish, "fr"));

        TemplateDataBlob english = blobWithDescription("""
                {"es":"Spanish","en":"English"}
                """, "nl");
        assertEquals("English", resolvedDescription(english, "fr"));
    }

    @Test
    void getDataInLanguageResolvesParamAndSetTexts() {
        TemplateDataBlob blob = TemplateDataBlob.parse("""
                {
                  "params": {
                    "foo": {
                      "label": { "fr": "French", "en": "English" },
                      "description": { "en": "Only English" },
                      "default": { "fr": "Défaut" },
                      "example": { "es": "Ejemplo" }
                    }
                  },
                  "sets": [ { "label": { "fr": "Groupe", "en": "Group" }, "params": ["foo"] } ]
                }
                """, "en");

        Map<String, Object> french = blob.getDataInLanguage("fr");
        Map<String, Object> foo = param(french, "foo");
        assertEquals("French", foo.get("label"));
        assertEquals("Only English", foo.get("description"));
        assertEquals("Défaut", foo.get("default"));
        assertNull(foo.get("example"));
        assertEquals("Groupe", Values.asMap(((List<?>) french.get("sets")).get(0)).get("label"));

        // 请求语言找不到时退到站点内容语言 / en
        Map<String, Object> spanish = blob.getDataInLanguage("es");
        assertEquals("Ejemplo", param(spanish, "foo").get("example"));
        assertEquals("Group", Values.asMap(((List<?>) spanish.get("sets")).get(0)).get("label"));
    }

    @Test
    void setLabelFallsBackToTheFirstValueWhenNoLanguageMatches() {
        // set 的 label 不是可选属性：全部语言都不匹配时退化成对象里的第一个值
        TemplateDataBlob blob = TemplateDataBlob.parse("""
                {
                  "params": { "foo": {} },
                  "sets": [ { "label": { "fr": "Groupe", "de": "Gruppe" }, "params": ["foo"] } ]
                }
                """, "en");
        Map<String, Object> spanish = blob.getDataInLanguage("es");
        assertEquals("Groupe", Values.asMap(((List<?>) spanish.get("sets")).get(0)).get("label"));
        // 可选属性则保持 null
        assertNull(blob.getDataInLanguage("es").get("description"));
    }

    @Test
    void languageFallbackChainIsDedupedAndEndsWithEnglish() {
        assertEquals(List.of("zh-hans", "zh", "en"), TemplateDataBlob.languageFallbackChain("zh-hans", "zh"));
        assertEquals(List.of("fr", "zh", "en"), TemplateDataBlob.languageFallbackChain("fr", "zh"));
        assertEquals(List.of("zh-hans", "zh", "en"), TemplateDataBlob.languageFallbackChain("zh_hans", "en"));
    }

    // ==================== 辅助 ====================

    private static Map<String, Object> parseOk(String json) {
        TemplateDataBlob blob = TemplateDataBlob.parse(json, ZH);
        assertTrue(blob.status().isOk(), () -> "应校验通过，实际：" + blob.status());
        return blob.getData();
    }

    private static void assertError(String json, String code, String... params) {
        TemplateDataBlob blob = TemplateDataBlob.parse(json, ZH);
        assertFalse(blob.status().isOk(), () -> "应校验失败：" + json);
        assertEquals(code, blob.status().code(), () -> "错误码：" + json);
        for (int i = 0; i < params.length; i++) {
            int index = i;
            assertEquals(params[i], blob.status().params().get(i),
                    () -> "错误参数 " + index + "：" + json);
        }
    }

    /** 与原版 PHPUnit 一致的宽松比较：忽略对象键顺序。 */
    private static void assertNormalized(String input, String expected) {
        assertEquals(canonical(expected), canonical(parseOk(input)));
    }

    private static String canonical(Object value) {
        return Json.stringify(canonicalValue(value));
    }

    private static String canonical(String json) {
        return canonical(Json.parse(json));
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(canonicalValue(item));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> param(Map<String, Object> data, String name) {
        return (Map<String, Object>) ((Map<String, Object>) data.get("params")).get(name);
    }

    private static TemplateDataBlob blobWithDescription(String description) {
        return blobWithDescription(description, ZH);
    }

    private static TemplateDataBlob blobWithDescription(String description, String contentLanguage) {
        return TemplateDataBlob.parse("{\"description\":" + description + ",\"params\":{}}", contentLanguage);
    }

    private static String resolvedDescription(TemplateDataBlob blob, String language) {
        return (String) blob.getDataInLanguage(language).get("description");
    }
}
