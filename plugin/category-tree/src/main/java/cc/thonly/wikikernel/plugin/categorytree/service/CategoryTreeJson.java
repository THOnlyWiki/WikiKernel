package cc.thonly.wikikernel.plugin.categorytree.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 序列化（无第三方依赖），语义对齐 PHP 的 {@code json_decode} / {@code json_encode}：
 * <ul>
 *     <li>对象 → {@link LinkedHashMap}（保留键顺序，键总是字符串）；数组 → {@link ArrayList}；</li>
 *     <li>字符串 → {@link String}；整数 → {@link Long}；小数 → {@link Double}；</li>
 *     <li>{@code true} / {@code false} → {@link Boolean}，{@code null} → {@code null}；</li>
 *     <li>解析失败（含尾部多余内容）抛 {@link CategoryTreeJson.JsonException}，等价于 {@code json_decode} 返回 {@code null}。</li>
 * </ul>
 * 自带解析器而不依赖 Spring / Jackson：插件只允许依赖 wiki-common，common 里没有 JSON 库。
 */
public final class CategoryTreeJson {

    private final String text;
    private int pos;

    private CategoryTreeJson(String text) {
        this.text = text;
    }

    /** JSON 语法错误。 */
    static final class JsonException extends RuntimeException {

        JsonException(String message) {
            super(message);
        }
    }

    /** 解析 JSON；输入为 null、空串、语法错误或尾部有多余内容都会抛 {@link JsonException}。 */
    static Object parse(String text) {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        CategoryTreeJson parser = new CategoryTreeJson(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw new JsonException("第 " + (parser.pos + 1) + " 个字符处有多余内容");
        }
        return value;
    }

    /** 解析 JSON，失败返回 {@code null}（对应 {@code json_decode} 失败）。 */
    public static Object parseOrNull(String text) {
        try {
            return parse(text);
        } catch (JsonException exception) {
            return null;
        }
    }

    /** 序列化为紧凑 JSON：对象输出 {@code {...}}、数组输出 {@code [...]}，保留键顺序。 */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    // ==================== 序列化 ====================

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (doubleValue == Math.rint(doubleValue) && !Double.isInfinite(doubleValue)) {
                out.append((long) doubleValue);
            } else {
                out.append(doubleValue);
            }
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ==================== 解析 ====================

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new JsonException("内容意外结束");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw new JsonException("对象键必须是字符串（第 " + (pos + 1) + " 个字符）");
            }
            String key = readString();
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != ':') {
                throw new JsonException("对象键后缺少 :（第 " + (pos + 1) + " 个字符）");
            }
            pos++;
            result.put(key, readValue());
            skipWhitespace();
            if (pos >= text.length()) {
                throw new JsonException("对象没有闭合");
            }
            char c = text.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return result;
            }
            throw new JsonException("对象中出现意外字符：" + c);
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (pos >= text.length()) {
                throw new JsonException("数组没有闭合");
            }
            char c = text.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return result;
            }
            throw new JsonException("数组中出现意外字符：" + c);
        }
    }

    private String readString() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new JsonException("字符串没有闭合");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw new JsonException("转义序列不完整");
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw new JsonException("\\u 转义不完整");
                    }
                    String hex = text.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException exception) {
                        throw new JsonException("非法的 \\u 转义：" + hex);
                    }
                }
                default -> throw new JsonException("非法的转义字符：\\" + escaped);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("非法的字面量（第 " + (pos + 1) + " 个字符）");
        }
        pos += literal.length();
        return value;
    }

    private Object readNumber() {
        int start = pos;
        if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
            pos++;
        }
        boolean decimal = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                decimal = decimal || c == '.' || c == 'e' || c == 'E';
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        if (token.isEmpty() || "-".equals(token) || "+".equals(token)) {
            throw new JsonException("非法的数字（第 " + (start + 1) + " 个字符）");
        }
        try {
            return decimal ? (Object) Double.valueOf(token) : (Object) Long.valueOf(token);
        } catch (NumberFormatException exception) {
            throw new JsonException("非法的数字：" + token);
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }
}
