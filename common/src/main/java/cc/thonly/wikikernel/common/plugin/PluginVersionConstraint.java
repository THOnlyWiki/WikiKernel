package cc.thonly.wikikernel.common.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本约束（仿 Maven 的简化版）：支持 {@code >=}、{@code >}、{@code <=}、{@code <}、{@code =}
 * 与**裸版本号**（等价于 {@code >=}，与 {@code plugin.requiresCore=0.0.1} 的直觉一致）；
 * 空 / null 表示不限制。
 * <p>
 * 比较规则：**只比较数字段**，忽略限定符（{@code 0.0.1-SNAPSHOT} 与 {@code 0.0.1} 视为相等，
 * 缺少的段按 0 处理，因此 {@code >=0.0.1} 在版本 {@code 0.0.1-SNAPSHOT} 上成立）。
 * 这样插件作者写 {@code >=0.0.1} 就能覆盖开发期的 SNAPSHOT，不需要在描述文件里写 SNAPSHOT。
 * <p>
 * 例：{@code ">=0.0.1"} / {@code ">1.2"} / {@code "<=2.0.0"} / {@code "=0.0.1"} / {@code "1.0"}（= ≥1.0）。
 */
public final class PluginVersionConstraint {

    /** 不限制任何版本。 */
    public static final PluginVersionConstraint ANY = new PluginVersionConstraint("", null, "");

    private enum Operator {
        GTE(">="), GT(">"), LTE("<="), LT("<"), EQ("=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        boolean test(int compared) {
            return switch (this) {
                case GTE -> compared >= 0;
                case GT -> compared > 0;
                case LTE -> compared <= 0;
                case LT -> compared < 0;
                case EQ -> compared == 0;
            };
        }
    }

    private final String raw;
    private final Operator operator;
    private final String version;

    private PluginVersionConstraint(String raw, Operator operator, String version) {
        this.raw = raw;
        this.operator = operator;
        this.version = version;
    }

    /** 解析约束文本（null / 空白 / 非法内容 → {@link #ANY}）。 */
    public static PluginVersionConstraint parse(String text) {
        if (text == null || text.isBlank()) {
            return ANY;
        }
        String trimmed = text.trim();
        Operator operator = null;
        String rest = trimmed;
        for (String symbol : List.of(">=", "<=", "==", ">", "<", "=")) {
            if (trimmed.startsWith(symbol)) {
                operator = switch (symbol) {
                    case ">=" -> Operator.GTE;
                    case "<=" -> Operator.LTE;
                    case "==", "=" -> Operator.EQ;
                    case ">" -> Operator.GT;
                    default -> Operator.LT;
                };
                rest = trimmed.substring(symbol.length()).trim();
                break;
            }
        }
        if (rest.isEmpty()) {
            // 只有操作符（如 ">="）：视为不限制，避免插件因笔误完全无法启动
            return ANY;
        }
        // 裸版本号 = >=（符合「至少要这个版本」的直觉）
        return new PluginVersionConstraint(trimmed, operator == null ? Operator.GTE : operator, rest);
    }

    /** 是否不限制版本。 */
    public boolean isAny() {
        return this.operator == null;
    }

    /** 判断给定版本是否满足本约束。 */
    public boolean matches(String candidate) {
        if (isAny()) {
            return true;
        }
        return this.operator.test(compare(candidate, this.version));
    }

    /** 原始文本（日志用）。 */
    public String raw() {
        return this.raw.isBlank() ? "" : this.raw;
    }

    @Override
    public String toString() {
        return isAny() ? "(不限制)" : this.raw;
    }

    /**
     * 版本比较：只比较数字段，忽略限定符（SNAPSHOT / rc 等）；返回负数 / 0 / 正数。
     */
    public static int compare(String left, String right) {
        List<Integer> a = numericSegments(left);
        List<Integer> b = numericSegments(right);
        int size = Math.max(a.size(), b.size());
        for (int index = 0; index < size; index++) {
            int x = index < a.size() ? a.get(index) : 0;
            int y = index < b.size() ? b.get(index) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** {@code "0.0.1-SNAPSHOT"} → {@code [0, 0, 1]}；非数字段（限定符）忽略。 */
    private static List<Integer> numericSegments(String version) {
        List<Integer> segments = new ArrayList<>();
        if (version == null || version.isBlank()) {
            return segments;
        }
        for (String token : version.trim().split("[.\\-_+\\s]+")) {
            int end = 0;
            while (end < token.length() && Character.isDigit(token.charAt(end))) {
                end++;
            }
            if (end == 0) {
                continue;
            }
            try {
                segments.add(Integer.parseInt(token.substring(0, end)));
            } catch (NumberFormatException ignored) {
                // 段太长（非法版本）：跳过该段，按 0 处理
            }
        }
        return segments;
    }
}
