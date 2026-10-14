package cc.thonly.wikikernel.plugin.templatedata.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Json}：解析 / 序列化（对象与数组的区分、键顺序、数字、转义）。
 */
class JsonTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        Object value = Json.parse("""
                {
                  "params": { "1": { "required": true, "type": "line", "aliases": ["a", 2] } },
                  "description": null,
                  "format": "inline",
                  "count": 3,
                  "ratio": 0.5
                }
                """);
        assertTrue(value instanceof Map);
        Map<String, Object> map = Values.asMap(value);
        assertEquals(null, map.get("description"));
        assertEquals("inline", map.get("format"));
        assertEquals(3L, map.get("count"));
        assertEquals(0.5, map.get("ratio"));
        Map<String, Object> one = Values.asMap(Values.asMap(map.get("params")).get("1"));
        assertEquals(Boolean.TRUE, one.get("required"));
        assertEquals(List.of("a", 2L), one.get("aliases"));
    }

    @Test
    void distinguishesEmptyObjectFromEmptyArray() {
        assertEquals("{}", Json.stringify(Json.parse("{}")));
        assertEquals("[]", Json.stringify(Json.parse("[]")));
        assertEquals("{\"params\":{}}", Json.stringify(Json.parse("{\"params\":{}}")));
        assertEquals("{\"params\":[]}", Json.stringify(Json.parse("{\"params\":[]}")));
    }

    @Test
    void keepsKeyOrderAndEscapesStrings() {
        assertEquals("{\"b\":1,\"a\":2}", Json.stringify(Json.parse("{\"b\":1,\"a\":2}")));
        assertEquals("{\"s\":\"引号\\\"制表\\t换行\\n\"}", Json.stringify(Json.parse("{\"s\":\"引号\\\"制表\\t换行\\n\"}")));
        assertEquals("{\"s\":\"中文/斜杠\"}", Json.stringify(Json.parse("{\"s\":\"\\u4e2d\\u6587\\/斜杠\"}")));
    }

    @Test
    void parsesUnicodeEscapesAndSurrogatePairs() {
        assertEquals("中文", Json.parse("\"\\u4e2d\\u6587\""));
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\""));
    }

    @Test
    void rejectsMalformedJson() {
        for (String invalid : new String[] { "", "{", "}", "[1,]", "{\"a\":}", "{a:1}", "\"x", "null x", "1 2" }) {
            assertThrows(Json.JsonException.class, () -> Json.parse(invalid), () -> "应解析失败：" + invalid);
        }
        assertEquals(null, Json.parseOrNull("{"));
        assertEquals(null, Json.parseOrNull(""));
    }

    @Test
    void nullLiteralParsesToNull() {
        assertEquals(null, Json.parse("null"));
        assertEquals("null", Json.stringify(Json.parse("null")));
    }

    @Test
    void integerAndFloatNumbersAreDistinguished() {
        // 与 PHP json_decode 一致：整数是 int、带小数点的是 float（校验别名时要区分）
        assertTrue(Json.parse("1") instanceof Long);
        assertTrue(Json.parse("1.0") instanceof Double);
        assertFalse(Json.parse("1") instanceof Double);
    }

    @Test
    void acceptsWhitespaceAroundTheDocument() {
        assertEquals(1L, Json.parse("  \n 1 \n "));
        assertEquals("{}", Json.stringify(Json.parse("\t{}\r\n")));
    }
}
