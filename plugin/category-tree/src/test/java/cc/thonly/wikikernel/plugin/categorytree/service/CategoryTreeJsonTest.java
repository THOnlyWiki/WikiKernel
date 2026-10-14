package cc.thonly.wikikernel.plugin.categorytree.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 极简 JSON 解析 / 序列化（对齐 PHP 的 json_decode / json_encode 语义）。 */
class CategoryTreeJsonTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        Map<?, ?> object = assertInstanceOf(Map.class,
                CategoryTreeJson.parse("{\"mode\":\"all\",\"depth\":2,\"showcount\":true,\"namespaces\":[0,14],"
                        + "\"hideprefix\":null}"));
        assertEquals("all", object.get("mode"));
        assertEquals(2L, object.get("depth"));
        assertEquals(Boolean.TRUE, object.get("showcount"));
        assertEquals(List.of(0L, 14L), object.get("namespaces"));
        assertNull(object.get("hideprefix"));
        assertTrue(object.containsKey("hideprefix"));
    }

    @Test
    void parsesEscapes() {
        assertEquals("a\"b\\c\nd", CategoryTreeJson.parse("\"a\\\"b\\\\c\\nd\""));
        assertEquals("中", CategoryTreeJson.parse("\"\\u4e2d\""));
        assertEquals(1.5, CategoryTreeJson.parse("1.5"));
        assertEquals(12345678901L, CategoryTreeJson.parse("12345678901"));
    }

    @Test
    void rejectsInvalidDocuments() {
        for (String text : List.of("", "{", "[1,", "{\"a\":}", "{}x", "nul", "tru", "\"未闭合")) {
            assertEquals(null, CategoryTreeJson.parseOrNull(text), text);
            assertThrows(CategoryTreeJson.JsonException.class, () -> CategoryTreeJson.parse(text), text);
        }
        assertThrows(CategoryTreeJson.JsonException.class, () -> CategoryTreeJson.parse(null));
    }

    @Test
    void stringifyIsCompactAndKeepsOrder() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", "categories");
        values.put("showcount", false);
        values.put("namespaces", List.of(0, 14));
        values.put("nil", null);
        values.put("text", "含\"引号\"与\n换行");
        assertEquals("{\"mode\":\"categories\",\"showcount\":false,\"namespaces\":[0,14],\"nil\":null,"
                + "\"text\":\"含\\\"引号\\\"与\\n换行\"}", CategoryTreeJson.stringify(values));
    }

    @Test
    void stringifyRoundTrips() {
        String json = "{\"a\":[1,2,{\"b\":\"c\"}],\"d\":true}";
        assertEquals(json, CategoryTreeJson.stringify(CategoryTreeJson.parse(json)));
    }
}
