package cc.thonly.wikikernel.wikitext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 行级差异（Myers O(ND)），用于页面版本的「差异对比页」。
 * <p>
 * 用法：{@link #diff(String, String)} 得到按先后顺序排列的行列表（相同 / 删除 / 新增），
 * 模板据此渲染两栏 diff 表；{@link #highlightPair(String, String)} 给「一行删除 + 一行新增」
 * 的替换对内联高亮（公共前缀 / 后缀之外的中间部分加粗，仿 MediaWiki 的 {@code .diffchange}）。
 * <p>
 * 内容过大（总行数 &gt; 4000 或编辑距离 &gt; 1200）时退化为「公共前缀 / 后缀 + 全删全增」，
 * 保证任何体积的页面都能打开差异页。
 */
public final class LineDiff {

    public enum Type {
        /** 两侧相同的行。 */
        SAME,
        /** 仅旧版本有的行。 */
        DELETE,
        /** 仅新版本有的行。 */
        ADD
    }

    /** 一行差异；{@code oldLine} / {@code newLine} 为 1 基行号，0 = 该侧没有这一行。 */
    public record Line(Type type, String text, int oldLine, int newLine) {

        public boolean same() {
            return type == Type.SAME;
        }

        public boolean removed() {
            return type == Type.DELETE;
        }

        public boolean added() {
            return type == Type.ADD;
        }
    }

    /** 替换对的内联高亮：公共前缀 / 中间变化段 / 公共后缀。 */
    public record Highlight(String before, String change, String after) {
    }

    /** 总行数上限：超过则退化，避免 Myers 在巨大文本上卡顿。 */
    private static final int MAX_TOTAL_LINES = 4000;

    /** 编辑距离上限：超过则退化（trace 的内存是 O(D²)）。 */
    private static final int MAX_EDIT_DISTANCE = 1200;

    private LineDiff() {
    }

    /** 行级差异（旧文本 → 新文本）。 */
    public static List<Line> diff(String oldText, String newText) {
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        if (oldLines.size() + newLines.size() > MAX_TOTAL_LINES) {
            return fallback(oldLines, newLines);
        }
        int n = oldLines.size();
        int m = newLines.size();
        Map<Integer, Integer> v = new HashMap<>();
        v.put(1, 0);
        List<Map<Integer, Integer>> trace = new ArrayList<>();
        for (int d = 0; d <= n + m; d++) {
            if (d > MAX_EDIT_DISTANCE) {
                return fallback(oldLines, newLines);
            }
            trace.add(new HashMap<>(v));
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v.getOrDefault(k - 1, -1) < v.getOrDefault(k + 1, -1))) {
                    x = v.getOrDefault(k + 1, 0);
                } else {
                    x = v.getOrDefault(k - 1, 0) + 1;
                }
                int y = x - k;
                while (x < n && y < m && oldLines.get(x).equals(newLines.get(y))) {
                    x++;
                    y++;
                }
                v.put(k, x);
                if (x >= n && y >= m) {
                    return backtrack(trace, oldLines, newLines);
                }
            }
        }
        return fallback(oldLines, newLines);
    }

    /** 文本 → 行列表（\r\n / \r / \n 都算换行；空文本 = 0 行；结尾换行不产生多余空行）。 */
    static List<String> splitLines(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(value.split("\\r\\n|\\r|\\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty() && (value.endsWith("\n") || value.endsWith("\r"))) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** 替换对的内联高亮：公共前缀与公共后缀之外的中间段。 */
    public static Highlight[] highlightPair(String oldLine, String newLine) {
        int max = Math.min(oldLine.length(), newLine.length());
        int prefix = 0;
        while (prefix < max && oldLine.charAt(prefix) == newLine.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && oldLine.charAt(oldLine.length() - 1 - suffix) == newLine.charAt(newLine.length() - 1 - suffix)) {
            suffix++;
        }
        return new Highlight[]{splitHighlight(oldLine, prefix, suffix), splitHighlight(newLine, prefix, suffix)};
    }

    private static Highlight splitHighlight(String text, int prefix, int suffix) {
        return new Highlight(
                text.substring(0, prefix),
                text.substring(prefix, text.length() - suffix),
                text.substring(text.length() - suffix));
    }

    /** Myers 回溯：从终点反向构造行列表。 */
    private static List<Line> backtrack(List<Map<Integer, Integer>> trace,
                                        List<String> a, List<String> b) {
        LinkedList<Line> result = new LinkedList<>();
        int x = a.size();
        int y = b.size();
        for (int d = trace.size() - 1; d >= 0; d--) {
            Map<Integer, Integer> v = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && v.getOrDefault(k - 1, -1) < v.getOrDefault(k + 1, -1))) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v.getOrDefault(prevK, 0);
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                result.addFirst(new Line(Type.SAME, a.get(x - 1), x, y));
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) {
                    result.addFirst(new Line(Type.ADD, b.get(y - 1), 0, y));
                    y--;
                } else {
                    result.addFirst(new Line(Type.DELETE, a.get(x - 1), x, 0));
                    x--;
                }
            }
        }
        return result;
    }

    /** 退化模式：公共前缀 / 后缀标相同，中间整段先删后增（保证结果正确，只是不够精细）。 */
    private static List<Line> fallback(List<String> oldLines, List<String> newLines) {
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }
        List<Line> result = new ArrayList<>();
        int oldNo = 1;
        int newNo = 1;
        for (int i = 0; i < prefix; i++) {
            result.add(new Line(Type.SAME, oldLines.get(i), oldNo++, newNo++));
        }
        for (int i = prefix; i < oldLines.size() - suffix; i++) {
            result.add(new Line(Type.DELETE, oldLines.get(i), oldNo++, 0));
        }
        for (int i = prefix; i < newLines.size() - suffix; i++) {
            result.add(new Line(Type.ADD, newLines.get(i), 0, newNo++));
        }
        for (int i = 0; i < suffix; i++) {
            result.add(new Line(Type.SAME, oldLines.get(oldLines.size() - suffix + i), oldNo++, newNo++));
        }
        return result;
    }
}
