package cc.thonly.wikikernel.plugin.templatedata.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 序列化（无第三方依赖），语义对齐 PHP 的 {@code json_decode} / {@code json_encode}：
 * <ul>
 *     <li>对象 → {@link LinkedHashMap}（保留键顺序，键总是字符串）；</li>
 *     <li>数组 → {@link ArrayList}；字符串 → {@link String}；整数 → {@link Long}；小数 → {@link Double}；</li>
 *     <li>{@code true}/{@code false} → {@link Boolean}，{@code null} → {@code null}；</li>
 *     <li>解析失败（含尾部多余内容）抛 {@link JsonException}，等价于 {@code json_decode} 返回 {@code null}。</li>
 * </ul>
 * 与 PHP 的差异（不影响语义）：序列化保留非 ASCII 字符原文（PHP 会输出 {@code &#92;uXXXX}）且不转义 {@code /}。
 * 之所以自带解析器而不用 Spring/Jackson：插件只允许依赖 wiki-common，common 里没有 JSON 库。
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** JSON 语法错误（位置信息仅供调试，用户看到的是本地化的 {@code templatedata-invalid-parse}）。 */
    public static final class JsonException extends RuntimeException {

        public JsonException(String message) {
            super(message);
        }
    }

    /** 解析 JSON；输入为 null、空串、语法错误或尾部有多余内容都抛 {@link JsonException}。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        Json parser = new Json(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw new JsonException("第 " + (parser.pos + 1) + " 个字符处有多余内容");
        }
        return value;
    }

    /** 解析 JSON，失败返回 null（对齐 {@code json_decode} 失败返回 null 的行为）。 */
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
        switch (value) {
            case null -> out.append("null");
            case String string -> writeString(out, string);
            case Boolean bool -> out.append(bool ? "true" : "false");
            case Double decimal -> out.append(formatDouble(decimal));
            case Float single -> out.append(formatDouble(single.doubleValue()));
            case Number integer -> out.append(integer.toString());
            case Map<?, ?> map -> writeObject(out, map);
            case Iterable<?> list -> writeArray(out, list);
            case Object[] array -> writeArray(out, List.of(array));
            default -> throw new JsonException("无法序列化的类型：" + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
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
    }

    private static void writeArray(StringBuilder out, Iterable<?> list) {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(out, item);
        }
        out.append(']');
    }

    /** 小数格式：整数值补 {@code .0}（与 PHP 7.1+ 的 json_encode 一致），非有限值退化为 null。 */
    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return (long) value + ".0";
        }
        return String.valueOf(value);
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
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
            throw new JsonException("意外的结尾");
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

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("第 " + (pos + 1) + " 个字符处不是合法的 JSON 值");
        }
        pos += literal.length();
        return value;
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw new JsonException("第 " + (pos + 1) + " 个字符处缺少对象键");
            }
            String key = readString();
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != ':') {
                throw new JsonException("第 " + (pos + 1) + " 个字符处缺少 ':'");
            }
            pos++;
            object.put(key, readValue());
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
                return object;
            }
            throw new JsonException("第 " + (pos + 1) + " 个字符处缺少 ',' 或 '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return array;
        }
        while (true) {
            array.add(readValue());
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
                return array;
            }
            throw new JsonException("第 " + (pos + 1) + " 个字符处缺少 ',' 或 ']'");
        }
    }

    private String readString() {
        pos++; // '"'
        StringBuilder value = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw new JsonException("字符串没有闭合");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw new JsonException("转义序列不完整");
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(readUnicodeEscape());
                default -> throw new JsonException("非法转义：\\" + escaped);
            }
        }
    }

    private char readUnicodeEscape() {
        if (pos + 4 > text.length()) {
            throw new JsonException("\\u 转义不完整");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos + i), 16);
            if (digit < 0) {
                throw new JsonException("非法的 \\u 转义");
            }
            code = code * 16 + digit;
        }
        pos += 4;
        return (char) code;
    }

    private Object readNumber() {
        int start = pos;
        if (pos < text.length() && text.charAt(pos) == '-') {
            pos++;
        }
        int digitsStart = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (pos == digitsStart) {
            throw new JsonException("第 " + (start + 1) + " 个字符处不是合法的 JSON 值");
        }
        boolean floating = false;
        if (pos < text.length() && text.charAt(pos) == '.') {
            floating = true;
            pos++;
            int fractionStart = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == fractionStart) {
                throw new JsonException("小数点后缺少数字");
            }
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            int exponentStart = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == exponentStart) {
                throw new JsonException("指数部分缺少数字");
            }
        }
        String raw = text.substring(start, pos);
        if (floating) {
            return Double.parseDouble(raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            // 超出 64 位：PHP 会退化成 float，这里保持一致
            return Double.parseDouble(raw);
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
