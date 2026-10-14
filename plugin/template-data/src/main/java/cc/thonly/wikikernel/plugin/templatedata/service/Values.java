package cc.thonly.wikikernel.plugin.templatedata.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP 语义小工具：TemplateData 的校验 / 规范化是从 PHP 直译过来的，这里集中处理几处
 * PHP 与 Java 语义不同的地方（{@code isset()}、真值判断、数组 ↔ 对象、字符串化）。
 */
public final class Values {

    private Values() {
    }

    /** 对象（json_decode 的 stdClass）视图；不是对象返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /** 数组（json_decode 的 array）视图；不是数组返回 null。 */
    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    /** 新对象（保序）。 */
    public static Map<String, Object> newMap() {
        return new LinkedHashMap<>();
    }

    /** 新数组。 */
    static List<Object> newList() {
        return new ArrayList<>();
    }

    /**
     * PHP 的 {@code isset($map[$key])}：键存在且值不是 null。
     * （PHP 里 {@code isset} 对 null 值返回 false，所以 {@code "x": null} 等同于没写。）
     */
    static boolean isset(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key) && map.get(key) != null;
    }

    /** PHP 的 {@code ??=}：键不存在或值为 null 时赋值（保序，不会改变已有键的位置）。 */
    static void putIfAbsent(Map<String, Object> map, String key, Object value) {
        if (!isset(map, key)) {
            map.put(key, value);
        }
    }

    /**
     * PHP 的字符串化：{@code true → "1"}、{@code false → ""}、数字按其文本、字符串原样。
     * 对象 / 数组这类无法字符串化的值返回 null（原版在 PHP 里会直接抛 TypeError）。
     */
    static String phpString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "";
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            // PHP 的 (string) 转换：整数部分不带小数点
            return number == Math.rint(number) && Math.abs(number) < 1e15
                    ? String.valueOf((long) number)
                    : String.valueOf(number);
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    /** PHP 的真值判断：{@code false}/空串/0/空数组/空对象/null 之外都是真。 */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return !string.isEmpty() && !"0".equals(string);
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    /** PHP 的 {@code ltrim()}（默认字符集：空格、\t、\n、\r、\0、\x0B）。 */
    static String ltrimPhp(String text) {
        int index = 0;
        while (index < text.length() && isPhpTrimChar(text.charAt(index))) {
            index++;
        }
        return text.substring(index);
    }

    /** PHP 的 {@code trim()}（默认字符集，比 Java 的 strip() 更保守）。 */
    public static String trimPhp(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isPhpTrimChar(text.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimChar(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isPhpTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    /**
     * PHP 的 {@code strtolower()}：只把 ASCII 大写字母转小写（PHP 的 strtolower 不处理多字节字符）。
     */
    public static String asciiLower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return out.toString();
    }

    /** 深拷贝（json_decode 出来的结构只有对象 / 数组 / 标量）。 */
    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    /** 深拷贝对象视图。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopyMap(Map<String, Object> map) {
        return (Map<String, Object>) deepCopy(map);
    }
}
