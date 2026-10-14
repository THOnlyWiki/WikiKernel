package cc.thonly.wikikernel.plugin.abusefilter.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具（滥用日志的变量快照用，避免引入额外依赖）：
 * 只支持 Map / List / String / 数字 / 布尔 / null；解析与序列化互为逆操作。
 */
public final class AbuseJson {

    private AbuseJson() {
    }

    /** 序列化（Map 的键按插入顺序输出）。 */
    public static String write(Map<String, Object> values) {
        StringBuilder json = new StringBuilder();
        writeValue(json, values);
        return json.toString();
    }

    /** 解析成 Map；空串 / 非法内容抛 {@link IllegalArgumentException}。 */
    public static Map<String, Object> parse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return Map.of();
        }
        Parser parser = new Parser(value);
        Object parsed = parser.parseValue();
        parser.skipWhitespace();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON 顶层必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    // ==================== 序列化 ====================

    private static void writeValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                writeString(json, String.valueOf(entry.getKey()));
                json.append(':');
                writeValue(json, entry.getValue());
            }
            json.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            json.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                writeValue(json, list.get(i));
            }
            json.append(']');
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            json.append(value);
            return;
        }
        writeString(json, String.valueOf(value));
    }

    private static void writeString(StringBuilder json, String text) {
        json.append('"');
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    // ==================== 解析 ====================

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("JSON 不完整");
            }
            char ch = text.charAt(pos);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseKeyword("true", Boolean.TRUE);
                case 'f' -> parseKeyword("false", Boolean.FALSE);
                case 'n' -> parseKeyword("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> value.append(escaped);
                }
            }
            throw new IllegalArgumentException("字符串没有结束引号");
        }

        private Object parseKeyword(String keyword, Object value) {
            if (!text.startsWith(keyword, pos)) {
                throw new IllegalArgumentException("非法 JSON：" + keyword);
            }
            pos += keyword.length();
            return value;
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new IllegalArgumentException("JSON 缺少 '" + expected + "'");
            }
            pos++;
        }
    }
}
