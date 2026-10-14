package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbuseFilter 表达式引擎：字面量 / 变量 / 运算符 / 函数 / 语法检查 / JSON 快照。
 * <p>
 * 用例从 core 迁移前的 {@code cc.thonly.wikikernel.abuse.AbuseExpressionTest} 重写而来
 * （同一个求值器，包名改成插件包）。
 */
class AbuseExpressionTest {

    private static AbuseContext context() {
        return new AbuseContext()
                .put("action", "edit")
                .put("page_title", "测试 页面")
                .put("page_namespace", 2L)
                .put("user_name", "BadUser123")
                .put("user_groups", List.of("用户", "自动确定用户"))
                .put("user_editcount", 10L)
                .put("summary", "编辑摘要")
                .put("added_lines", List.of("spam http://example.com", "新增内容"))
                .put("removed_lines", List.of("删除内容"))
                .put("new_wikitext", "spam http://example.com")
                .put("ip", "203.0.113.7");
    }

    private static boolean test(String pattern) {
        return AbuseCondition.compile(pattern).test(context());
    }

    @Test
    void comparisonsAndBooleans() {
        assertTrue(test("action == \"edit\""));
        assertTrue(test("action != 'move'"));
        assertTrue(test("page_namespace == 2"));
        assertFalse(test("user_editcount >= 10 & page_namespace == 0"));
        assertTrue(test("!(action == \"move\")"));
        assertTrue(test("action == \"move\" | action == \"edit\""));
        assertTrue(test("action == \"edit\" and not false"));
    }

    @Test
    void inContainsLikeRegex() {
        assertTrue(test("\"自动确定用户\" in user_groups"));
        assertFalse(test("\"管理员\" in user_groups"));
        assertTrue(test("user_name contains \"Bad\""));
        assertTrue(test("user_name like \"%user%\""));
        assertFalse(test("user_name like \"user%\""));
        assertTrue(test("user_name rlike \"^Bad\""));
        assertFalse(test("user_name rlike \"^bad\""));
        assertTrue(test("user_name irlike \"^bad\""));
        assertTrue(test("\"新增内容\" in added_lines"));
        assertTrue(test("added_lines contains \"spam http://example.com\""));
    }

    @Test
    void functions() {
        assertTrue(test("lcase(user_name) == \"baduser123\""));
        assertTrue(test("ucase(user_name) == \"BADUSER123\""));
        assertTrue(test("length(user_name) == 10"));
        assertTrue(test("count(added_lines) == 2"));
        assertTrue(test("contains_any(user_name, \"nobody\", \"BadUser\" )"));
        assertFalse(test("contains_all(user_name, \"bad\", \"xyz\")"));
        assertTrue(test("equals_to_any(action, \"edit\", \"createaccount\")"));
        assertTrue(test("strpos(user_name, \"User\") == 3"));
        assertTrue(test("substr(user_name, 0, 3) == \"Bad\""));
        assertTrue(test("replace(user_name, \"Bad\", \"Good\") == \"GoodUser123\""));
        assertTrue(test("rmwhitespace(\"a b c\") == \"abc\""));
        assertTrue(test("ccnorm(\"P4YPAL\") == \"paypal\""));
        assertTrue(test("ip_in_range(ip, \"203.0.113.0/24\")"));
        assertFalse(test("ip_in_range(ip, \"203.0.114.0/24\")"));
        assertTrue(test("int(\"42\") == 42"));
        assertTrue(test("bool(1) and not bool(\"\")"));
        assertTrue(test("urlencode(\"a b\") == \"a+b\""));
    }

    @Test
    void arithmeticAndPrecedence() {
        assertTrue(test("1 + 2 * 3 == 7"));
        assertTrue(test("(1 + 2) * 3 == 9"));
        assertTrue(test("2 ** 3 == 8"));
        assertTrue(test("user_editcount % 3 == 1"));
        assertTrue(test("# 注释\n// 另一条注释\ntrue"));
    }

    @Test
    void syntaxCheckedOnCompile() {
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile("action =="));
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile("unknown_variable == 1"));
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile("no_such_function(1)"));
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile("(action == \"edit\""));
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile(""));
        assertThrows(AbuseCondition.SyntaxException.class, () -> AbuseCondition.compile("\"未闭合"));
    }

    @Test
    void unknownVariablesReportLocalizableMessageKeys() {
        AbuseCondition.SyntaxException exception = assertThrows(AbuseCondition.SyntaxException.class,
                () -> AbuseCondition.compile("unknown_variable == 1"));
        assertEquals("abusefilter.error.unknownVariable", exception.messageKey());
        assertEquals(List.of("unknown_variable"), List.of(exception.args()));
    }

    @Test
    void uploadVariablesAreKnown() {
        AbuseContext upload = new AbuseContext()
                .put("action", "upload")
                .put("file_size", 2048L)
                .put("file_mime", "image/png")
                .put("page_title", "图片.png");
        assertTrue(AbuseCondition.compile("action == \"upload\" & file_size > 1024").test(upload));
        assertTrue(AbuseCondition.compile("file_mime == \"image/png\"").test(upload));
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", "edit");
        values.put("count", 3L);
        values.put("ratio", 0.5);
        values.put("flag", true);
        values.put("lines", List.of("a", "b"));
        values.put("empty", "");
        String json = AbuseJson.write(values);
        Map<String, Object> parsed = AbuseJson.parse(json);
        assertEquals("edit", parsed.get("action"));
        assertEquals(3L, ((Number) parsed.get("count")).longValue());
        assertEquals(0.5, ((Number) parsed.get("ratio")).doubleValue());
        assertEquals(Boolean.TRUE, parsed.get("flag"));
        assertEquals(List.of("a", "b"), parsed.get("lines"));
        assertEquals("", parsed.get("empty"));

        // 中文 / 引号 / 换行也要能原样往返（日志详情页直接展示快照）
        String text = "标题 \"引号\"\n换行";
        assertEquals(text, AbuseJson.parse(AbuseJson.write(Map.of("title", text))).get("title"));
    }
}
