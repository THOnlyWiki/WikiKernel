package cc.thonly.wikikernel.plugin.abusefilter.service;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AbuseFilter 表达式的取值助手与内置函数（与 MediaWiki AbuseFilter 的常用函数对齐）：
 * 类型转换、比较、以及 {@link #callFunction} 里的函数集合。
 * <p>
 * 支持的值类型：String / Long / Double / Boolean / List；比较遵循 MediaWiki 的宽松规则
 * （两侧都是数字按数值比较，否则按字符串比较；字符串转布尔时空串、"0"、"false" 为假）。
 */
public final class AbuseValues {

    private AbuseValues() {
    }

    /** 常见易混淆字符映射（{@code ccnorm} 用，简化版）。 */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'l'), Map.entry('2', 'z'), Map.entry('3', 'e'),
            Map.entry('4', 'a'), Map.entry('5', 's'), Map.entry('6', 'b'), Map.entry('7', 't'),
            Map.entry('8', 'b'), Map.entry('9', 'g'), Map.entry('@', 'a'), Map.entry('$', 's'),
            Map.entry('!', 'i'), Map.entry('|', 'l'), Map.entry('+', 't'), Map.entry('(', 'c'),
            Map.entry(')', ')'), Map.entry('[', 'c'), Map.entry(']', ')'), Map.entry('{', 'c'),
            Map.entry('}', ')'), Map.entry('\\', '/'), Map.entry('€', 'e'), Map.entry('£', 'l'));

    // ==================== 类型转换 ====================

    public static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !"0".equals(text) && !"false".equalsIgnoreCase(text);
    }

    public static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public static long toLong(Object value) {
        return (long) toDouble(value);
    }

    public static String toStringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double number) {
            return number == Math.floor(number) && !number.isInfinite()
                    ? String.valueOf(number.longValue()) : String.valueOf(number);
        }
        return String.valueOf(value);
    }

    /** 宽松相等：数字按数值，其它按字符串（忽略大小写关闭）。 */
    public static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number || right instanceof Number) {
            return toDouble(left) == toDouble(right);
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return toBoolean(left) == toBoolean(right);
        }
        return toStringValue(left).equals(toStringValue(right));
    }

    /** 比较（用于 &lt; &gt; &lt;= &gt;=）；两侧数字按数值，否则按字符串。 */
    public static int compare(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            return Double.compare(toDouble(left), toDouble(right));
        }
        return toStringValue(left).compareTo(toStringValue(right));
    }

    // ==================== 字符串 / 列表比较操作符 ====================

    /** {@code in}：右边是列表 → 是否包含（宽松相等）；右边是字符串 → 子串判断。 */
    public static boolean in(Object needle, Object haystack) {
        if (haystack instanceof List<?> list) {
            return list.stream().anyMatch(item -> equalsValue(needle, item));
        }
        return toStringValue(haystack).contains(toStringValue(needle));
    }

    /** {@code contains}：左边是列表 → 是否包含；否则字符串包含。 */
    public static boolean contains(Object haystack, Object needle) {
        if (haystack instanceof List<?> list) {
            return list.stream().anyMatch(item -> equalsValue(needle, item));
        }
        return toStringValue(haystack).contains(toStringValue(needle));
    }

    /** SQL 风格 {@code like}（{@code %} 任意、{@code _} 单字符；不区分大小写）。 */
    public static boolean like(Object value, Object pattern) {
        String regex = likeToRegex(toStringValue(pattern));
        return toStringValue(value).toLowerCase(Locale.ROOT).matches(regex);
    }

    private static String likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return "(?s)" + regex;
    }

    /** 正则匹配（{@code rlike} 区分大小写，{@code irlike} 不区分）。 */
    public static boolean regex(Object value, Object pattern, boolean ignoreCase) {
        String text = toStringValue(value);
        String regex = toStringValue(pattern);
        try {
            Pattern compiled = Pattern.compile(regex,
                    ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
            return compiled.matcher(text).find();
        } catch (PatternSyntaxException exception) {
            return false;
        }
    }

    // ==================== 内置函数 ====================

    /** 函数名是否已知（保存过滤器时校验）。 */
    public static boolean isKnownFunction(String name) {
        return switch (name) {
            case "lcase", "ucase", "length", "count", "contains_any", "contains_all", "equals_to_any",
                 "strpos", "substr", "replace", "norm", "ccnorm", "rmwhitespace", "specialratio",
                 "ip_in_range", "urlencode", "int", "float", "string", "bool", "rescape" -> true;
            default -> false;
        };
    }

    /** 调用内置函数；未知函数抛 {@link IllegalArgumentException}。 */
    public static Object callFunction(String name, List<Object> args) {
        return switch (name) {
            case "lcase" -> toStringValue(arg(args, 0)).toLowerCase(Locale.ROOT);
            case "ucase" -> toStringValue(arg(args, 0)).toUpperCase(Locale.ROOT);
            case "length" -> length(args);
            case "count" -> count(args);
            case "contains_any" -> containsAny(args, true);
            case "contains_all" -> containsAny(args, false);
            case "equals_to_any" -> equalsToAny(args);
            case "strpos" -> strpos(args);
            case "substr" -> substr(args);
            case "replace" -> toStringValue(arg(args, 0))
                    .replace(toStringValue(arg(args, 1)), toStringValue(arg(args, 2)));
            case "norm" -> norm(toStringValue(arg(args, 0)), false);
            case "ccnorm" -> norm(toStringValue(arg(args, 0)), true);
            case "rmwhitespace" -> toStringValue(arg(args, 0)).replaceAll("\\s+", "");
            case "specialratio" -> specialRatio(toStringValue(arg(args, 0)));
            case "ip_in_range" -> ipInRange(args);
            case "urlencode" -> URLEncoder.encode(toStringValue(arg(args, 0)), StandardCharsets.UTF_8);
            case "int" -> toLong(arg(args, 0));
            case "float" -> toDouble(arg(args, 0));
            case "string" -> toStringValue(arg(args, 0));
            case "bool" -> toBoolean(arg(args, 0));
            case "rescape" -> Pattern.quote(toStringValue(arg(args, 0)));
            default -> throw new IllegalArgumentException("未知函数：" + name);
        };
    }

    private static Object length(List<Object> args) {
        Object value = arg(args, 0);
        if (value instanceof List<?> list) {
            return (long) list.size();
        }
        return (long) toStringValue(value).length();
    }

    private static Object count(List<Object> args) {
        Object value = arg(args, 0);
        if (value instanceof List<?> list) {
            return (long) list.size();
        }
        return (long) toStringValue(value).length();
    }

    /** {@code contains_any}/{@code contains_all}：第一个参数可以是字符串或列表。 */
    private static Object containsAny(List<Object> args, boolean any) {
        Object haystack = arg(args, 0);
        List<Object> needles = args.subList(1, args.size());
        if (needles.isEmpty()) {
            return any ? false : true;
        }
        for (Object needle : needles) {
            boolean hit = haystack instanceof List<?> list
                    ? list.stream().anyMatch(item -> equalsValue(item, needle))
                    : toStringValue(haystack).contains(toStringValue(needle));
            if (any && hit) {
                return true;
            }
            if (!any && !hit) {
                return false;
            }
        }
        return !any;
    }

    private static Object equalsToAny(List<Object> args) {
        Object value = arg(args, 0);
        return args.subList(1, args.size()).stream().anyMatch(other -> equalsValue(value, other));
    }

    private static Object strpos(List<Object> args) {
        int index = toStringValue(arg(args, 0)).indexOf(toStringValue(arg(args, 1)));
        return (long) index;
    }

    private static Object substr(List<Object> args) {
        String text = toStringValue(arg(args, 0));
        int start = (int) toLong(arg(args, 1));
        if (start < 0) {
            start = Math.max(0, text.length() + start);
        }
        if (start >= text.length()) {
            return "";
        }
        if (args.size() < 3) {
            return text.substring(start);
        }
        int length = (int) toLong(args.get(2));
        if (length < 0) {
            return text.substring(start, Math.max(start, text.length() + length));
        }
        return text.substring(start, Math.min(text.length(), start + length));
    }

    /** {@code norm}：NFKC + 去掉标点 / 控制字符 + 转小写；{@code ccnorm} 额外做易混淆字符映射。 */
    private static String norm(String text, boolean confusables) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        for (char ch : normalized.toCharArray()) {
            if (confusables) {
                Character mapped = CONFUSABLES.get(ch);
                if (mapped != null) {
                    result.append(mapped);
                    continue;
                }
            }
            if (Character.isLetterOrDigit(ch) || Character.isWhitespace(ch)) {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static double specialRatio(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        long special = text.chars().filter(ch -> !Character.isLetterOrDigit(ch)).count();
        return (double) special / text.length();
    }

    private static Object ipInRange(List<Object> args) {
        String ip = toStringValue(arg(args, 0));
        String range = toStringValue(arg(args, 1));
        if (ip.equals(range)) {
            return true;
        }
        if (!range.contains("/")) {
            return false;
        }
        try {
            String[] parts = range.split("/", 2);
            byte[] address = InetAddress.getByName(ip).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            int maxBits = address.length * 8;
            if (prefix < 0 || prefix > maxBits) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - (bit % 8));
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static Object arg(List<Object> args, int index) {
        if (index >= args.size()) {
            throw new IllegalArgumentException("函数参数不足（缺少第 " + (index + 1) + " 个参数）");
        }
        return args.get(index);
    }

    /** 列表字面量辅助（测试用）。 */
    public static List<Object> listOf(Object... values) {
        return new ArrayList<>(List.of(values));
    }
}
