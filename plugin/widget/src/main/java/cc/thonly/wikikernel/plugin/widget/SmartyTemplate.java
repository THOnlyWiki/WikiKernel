package cc.thonly.wikikernel.plugin.widget;

import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迷你 Smarty 模板引擎（仿 MediaWiki Widgets 扩展的兼容子集）。
 * <p>
 * 微件页内容是 HTML + {@code <!--{ ... }-->} 标签；支持的子集：
 * <ul>
 *     <li>变量输出：{@code <!--{$id}-->}、点号路径 {@code <!--{$a.b.c}-->}、下标 {@code <!--{$a[0]}-->}；</li>
 *     <li>修饰符：{@code escape:'html'|'url'|'js'}、{@code urlencode}、{@code default:'x'}、
 *     {@code lower}、{@code upper}、{@code replace:'a':'b'}、{@code strip_tags}、{@code trim}（可链式叠加）；</li>
 *     <li>条件：{@code <!--{if $x}-->} / {@code elseif} / {@code else} / {@code /if}
 *     （支持 {@code $x}、{@code !$x}、{@code ==}、{@code !=}、{@code &&}、{@code ||}）；</li>
 *     <li>循环：{@code <!--{foreach from=$items item=x}-->} … {@code <!--{/foreach}-->}（列表或映射的值）；</li>
 *     <li>注释：{@code <!--{* ... *}-->} 不输出。</li>
 * </ul>
 * 与 Smarty 一致，输出**不做自动转义**：模板作者负责对引用参数加 {@code |escape}；
 * 无法识别的修饰符原样跳过，语法错误抛 {@link IllegalArgumentException}。
 */
public final class SmartyTemplate {

    /** 标签分隔符：<!--{ ... }-->（MediaWiki Widgets 用的 Smarty 配置）。 */
    private static final Pattern TAG = Pattern.compile("<!--\\{(.*?)\\}-->", Pattern.DOTALL);

    private final List<Node> nodes;

    private SmartyTemplate(List<Node> nodes) {
        this.nodes = nodes;
    }

    /** 编译模板；语法错误（未闭合的 if / foreach 等）抛 {@link IllegalArgumentException}。 */
    public static SmartyTemplate compile(String source) {
        List<Segment> segments = split(source == null ? "" : source);
        int[] index = {0};
        List<Node> nodes = parseNodes(segments, index);
        if (index[0] < segments.size()) {
            throw new IllegalArgumentException("多余的结束标签：" + segments.get(index[0]).value());
        }
        return new SmartyTemplate(nodes);
    }

    /** 用参数渲染模板。 */
    public String render(Map<String, Object> params) {
        StringBuilder out = new StringBuilder();
        renderNodes(this.nodes, params == null ? Map.of() : params, new HashMap<>(), out);
        return out.toString();
    }

    // ==================== 解析 ====================

    /** 文本片段 / 标签片段。 */
    private record Segment(boolean text, String value) {
    }

    private static List<Segment> split(String source) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = TAG.matcher(source);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                segments.add(new Segment(true, source.substring(last, matcher.start())));
            }
            segments.add(new Segment(false, matcher.group(1)));
            last = matcher.end();
        }
        if (last < source.length()) {
            segments.add(new Segment(true, source.substring(last)));
        }
        return segments;
    }

    private sealed interface Node permits Text, Output, Condition, Loop {
    }

    private record Text(String value) implements Node {
    }

    private record Output(String expression) implements Node {
    }

    private record Condition(String expression, List<Node> thenNodes, List<Node> elseNodes) implements Node {
    }

    private record Loop(String fromExpression, String itemName, List<Node> body) implements Node {
    }

    /** 解析节点列表；遇到 else / elseif / 结束标签（不消费）时返回。 */
    private static List<Node> parseNodes(List<Segment> segments, int[] index) {
        List<Node> nodes = new ArrayList<>();
        while (index[0] < segments.size()) {
            Segment segment = segments.get(index[0]);
            if (segment.text()) {
                nodes.add(new Text(segment.value()));
                index[0]++;
                continue;
            }
            String tag = segment.value().trim();
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.startsWith("/") || lower.equals("else") || lower.startsWith("elseif")) {
                break; // 控制边界：交给 parseIf / parseForeach 处理
            }
            index[0]++;
            if (tag.startsWith("*") && tag.endsWith("*")) {
                continue; // Smarty 注释
            }
            if (lower.startsWith("if ")) {
                nodes.add(parseIf(tag.substring(2).trim(), segments, index));
            } else if (lower.startsWith("foreach")) {
                nodes.add(parseForeach(tag, segments, index));
            } else {
                nodes.add(new Output(tag));
            }
        }
        return nodes;
    }

    /** 解析 if / elseif 链（调用时 if / elseif 标签已消费）。 */
    private static Condition parseIf(String condition, List<Segment> segments, int[] index) {
        List<Node> thenNodes = parseNodes(segments, index);
        if (index[0] >= segments.size()) {
            throw new IllegalArgumentException("if 未闭合：<!--{if " + condition + "}-->");
        }
        String boundary = segments.get(index[0]).value().trim();
        String lower = boundary.toLowerCase(Locale.ROOT);
        if (lower.startsWith("elseif")) {
            index[0]++;
            List<Node> elseNodes = List.of(parseIf(boundary.substring("elseif".length()).trim(), segments, index));
            return new Condition(condition, thenNodes, elseNodes);
        }
        if (lower.equals("else")) {
            index[0]++;
            List<Node> elseNodes = parseNodes(segments, index);
            expect(segments, index, "/if");
            return new Condition(condition, thenNodes, elseNodes);
        }
        if (lower.equals("/if")) {
            index[0]++;
            return new Condition(condition, thenNodes, List.of());
        }
        throw new IllegalArgumentException("if 标签错误：" + boundary);
    }

    private static Loop parseForeach(String tag, List<Segment> segments, int[] index) {
        String from = null;
        String item = null;
        for (String token : tag.split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = unquote(token.substring(equals + 1).trim());
            if ("from".equals(key)) {
                from = value;
            } else if ("item".equals(key)) {
                item = value;
            }
        }
        if (from == null || item == null) {
            throw new IllegalArgumentException("foreach 需要 from 与 item：" + tag);
        }
        List<Node> body = parseNodes(segments, index);
        expect(segments, index, "/foreach");
        return new Loop(from, item.startsWith("$") ? item.substring(1) : item, body);
    }

    private static void expect(List<Segment> segments, int[] index, String name) {
        if (index[0] >= segments.size()
                || !segments.get(index[0]).value().trim().toLowerCase(Locale.ROOT).equals(name)) {
            throw new IllegalArgumentException("缺少结束标签 <!--{" + name + "}-->");
        }
        index[0]++;
    }

    // ==================== 渲染 ====================

    private static void renderNodes(List<Node> nodes, Map<String, Object> params,
                                    Map<String, Object> scope, StringBuilder out) {
        for (Node node : nodes) {
            if (node instanceof Text text) {
                out.append(text.value());
            } else if (node instanceof Output output) {
                out.append(evaluateOutput(output.expression(), params, scope));
            } else if (node instanceof Condition condition) {
                List<Node> branch = evaluateCondition(condition.expression(), params, scope)
                        ? condition.thenNodes() : condition.elseNodes();
                renderNodes(branch, params, scope, out);
            } else if (node instanceof Loop loop) {
                Object value = resolvePath(loop.fromExpression(), params, scope);
                if (value instanceof List<?> list) {
                    for (Object element : list) {
                        renderLoopItem(loop, element, params, scope, out);
                    }
                } else if (value instanceof Map<?, ?> map) {
                    for (Object element : map.values()) {
                        renderLoopItem(loop, element, params, scope, out);
                    }
                }
            }
        }
    }

    private static void renderLoopItem(Loop loop, Object element, Map<String, Object> params,
                                       Map<String, Object> scope, StringBuilder out) {
        Map<String, Object> child = new HashMap<>(scope);
        child.put(loop.itemName(), element);
        renderNodes(loop.body(), params, child, out);
    }

    /** 输出表达式：变量 + 可链式修饰符（{@code $x|escape:'url'|default:'/'}）。 */
    private static String evaluateOutput(String expression, Map<String, Object> params,
                                         Map<String, Object> scope) {
        List<String> parts = splitTopLevel(expression, '|');
        Object value = expressionValue(parts.get(0).trim(), params, scope);
        for (int i = 1; i < parts.size(); i++) {
            value = applyModifier(value, parts.get(i).trim());
        }
        return stringify(value);
    }

    /** 变量（{@code $a.b[0]}）或字面量。 */
    private static Object expressionValue(String expression, Map<String, Object> params,
                                          Map<String, Object> scope) {
        String value = expression.trim();
        if (value.startsWith("$")) {
            return resolvePath(value, params, scope);
        }
        return unquote(value);
    }

    private static Object resolvePath(String expression, Map<String, Object> params,
                                      Map<String, Object> scope) {
        String value = expression.trim();
        if (!value.startsWith("$")) {
            return value;
        }
        int end = 1;
        while (end < value.length() && isNameChar(value.charAt(end))) {
            end++;
        }
        String name = value.substring(1, end);
        Object current = scope.containsKey(name) ? scope.get(name) : params.get(name);
        int i = end;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < value.length() && isNameChar(value.charAt(i))) {
                    i++;
                }
                if (start == i) {
                    break;
                }
                current = indexValue(current, value.substring(start, i));
            } else if (c == '[') {
                int close = value.indexOf(']', i);
                if (close < 0) {
                    break;
                }
                current = indexValue(current, unquote(value.substring(i + 1, close).trim()));
                i = close + 1;
            } else {
                break;
            }
        }
        return current;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static Object indexValue(Object current, String key) {
        if (current instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (current instanceof List<?> list) {
            try {
                int index = Integer.parseInt(key);
                return index >= 0 && index < list.size() ? list.get(index) : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static Object applyModifier(Object value, String modifier) {
        List<String> tokens = splitTopLevel(modifier, ':');
        String name = tokens.get(0).trim().toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            args.add(unquote(tokens.get(i).trim()));
        }
        String text = stringify(value);
        return switch (name) {
            case "escape" -> {
                String type = args.isEmpty() ? "html" : args.get(0).toLowerCase(Locale.ROOT);
                yield switch (type) {
                    case "url" -> urlEncode(text);
                    case "js" -> escapeJs(text);
                    default -> HtmlUtils.htmlEscape(text);
                };
            }
            case "urlencode" -> urlEncode(text);
            case "default" -> isEmptyValue(value) && !args.isEmpty() ? args.get(0) : value;
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "replace" -> args.size() >= 2 ? text.replace(args.get(0), args.get(1)) : value;
            case "strip_tags" -> text.replaceAll("<[^>]*>", "");
            case "trim" -> text.trim();
            default -> value;
        };
    }

    /** 条件：{@code ||} 分组（任一为真）内再按 {@code &&}（全部为真）。 */
    private static boolean evaluateCondition(String expression, Map<String, Object> params,
                                             Map<String, Object> scope) {
        for (String orPart : splitStringTopLevel(expression, "||")) {
            boolean all = true;
            for (String andPart : splitStringTopLevel(orPart, "&&")) {
                if (!evaluateSingleCondition(andPart, params, scope)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateSingleCondition(String expression, Map<String, Object> params,
                                                   Map<String, Object> scope) {
        String value = expression.trim();
        if (value.isEmpty()) {
            return false;
        }
        int equals = indexOfTopLevel(value, "==");
        if (equals >= 0) {
            return compare(operand(value.substring(0, equals), params, scope),
                    operand(value.substring(equals + 2), params, scope)) == 0;
        }
        int notEquals = indexOfTopLevel(value, "!=");
        if (notEquals >= 0) {
            return compare(operand(value.substring(0, notEquals), params, scope),
                    operand(value.substring(notEquals + 2), params, scope)) != 0;
        }
        if (value.startsWith("!")) {
            return !truthy(operand(value.substring(1), params, scope));
        }
        return truthy(operand(value, params, scope));
    }

    private static Object operand(String expression, Map<String, Object> params,
                                  Map<String, Object> scope) {
        String value = expression.trim();
        if (value.startsWith("$")) {
            return resolvePath(value, params, scope);
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            default -> unquote(value);
        };
    }

    private static int compare(Object left, Object right) {
        Double leftNumber = toNumber(left);
        Double rightNumber = toNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        return stringify(left).compareTo(stringify(right));
    }

    private static Double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && text.trim().matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(text.trim());
        }
        return null;
    }

    /** 仿 PHP 真值判断：null / false / 空串 / "0" / 0 / 空集合为假。 */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty() && !"0".equals(text);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static boolean isEmptyValue(Object value) {
        return !truthy(value);
    }

    /** 仿 PHP 输出：null / false / 集合 → 空；true → "1"。 */
    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "";
        }
        if (value instanceof List || value instanceof Map) {
            return "";
        }
        return String.valueOf(value);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("</", "<\\/");
    }

    private static String unquote(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() >= 2
                && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 按分隔符切分（引号内 / 括号内不切）。 */
    private static List<String> splitTopLevel(String text, char separator) {
        return splitStringTopLevel(text, String.valueOf(separator));
    }

    /** 按字符串分隔符切分（引号内 / 括号内不切）。 */
    private static List<String> splitStringTopLevel(String text, String token) {
        List<String> parts = new ArrayList<>();
        char quote = 0;
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i + token.length() <= text.length()) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                i++;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth <= 0 && text.startsWith(token, i)) {
                parts.add(text.substring(start, i));
                i += token.length();
                start = i;
                continue;
            }
            i++;
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 顶层查找标记（引号内 / 括号内不算），找不到返回 -1。 */
    private static int indexOfTopLevel(String text, String token) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i + token.length() <= text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && text.startsWith(token, i)) {
                return i;
            }
        }
        return -1;
    }
}
