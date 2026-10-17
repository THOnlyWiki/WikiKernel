package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.service.PageSearchStore.PageRow;

import java.util.List;
import java.util.Locale;

/**
 * DPL 结果输出：默认输出 wikitext 无序列表（交给 core 渲染成 {@code <ul>}）；
 * 支持 {@code mode=unordered/ordered/inline/none} 与常见的 {@code format=开始,分隔,结束}。
 * <p>
 * 不直接拼 {@code <a>}：项目标题走 {@code [[…]]}，这样红链、命名空间、转义都由 core 统一处理。
 */
final class DplFormatter {

    private DplFormatter() {
    }

    static String format(List<PageRow> rows, DplArguments args) {
        if (rows.isEmpty()) {
            return "";
        }
        String format = args.first("format");
        if (format != null && !format.isBlank()) {
            return formatWithCustom(rows, args, format);
        }
        String mode = args.first("mode", "unordered").trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        if ("inline".equals(mode)) {
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    out.append(" · ");
                }
                out.append(item(rows.get(i), args));
            }
            out.append('\n');
            return out.toString();
        }
        if ("none".equals(mode)) {
            for (PageRow row : rows) {
                out.append(item(row, args)).append('\n');
            }
            return out.toString();
        }
        String marker = "ordered".equals(mode) ? "# " : "* ";
        for (PageRow row : rows) {
            out.append(marker).append(item(row, args)).append('\n');
        }
        return out.toString();
    }

    /**
     * {@code format=开始,分隔,结束}：开始 / 结束只输出一次，分隔放在两条之间。
     * 分隔里可以写 {@code %PAGE%} / {@code %TITLE%} / {@code %PAGENAME%} / {@code %NR%}。
     */
    private static String formatWithCustom(List<PageRow> rows, DplArguments args, String format) {
        String[] parts = format.split(",", -1);
        if (parts.length >= 3) {
            StringBuilder out = new StringBuilder(parts[0]);
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    out.append(parts[1]);
                }
                out.append(item(rows.get(i), args));
            }
            out.append(parts[2]);
            return out.toString();
        }
        // 只有一段：把它当作每条结果的模板（支持 %PAGE% 等占位）。
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            out.append(replacePlaceholders(parts[0], rows.get(i), args, i));
        }
        return out.toString();
    }

    private static String replacePlaceholders(String text, PageRow row, DplArguments args, int index) {
        return text
                .replace("%PAGE%", item(row, args))
                .replace("%TITLE%", row.title())
                .replace("%PAGENAME%", displayTitle(row.title(), args))
                .replace("%NR%", String.valueOf(index + 1));
    }

    private static String item(PageRow row, DplArguments args) {
        String title = row.title();
        String display = displayTitle(title, args);
        if (args.bool("escapelinks", false)) {
            return display;
        }
        return display.equals(title) ? "[[" + title + "]]" : "[[" + title + "|" + display + "]]";
    }

    private static String displayTitle(String title, DplArguments args) {
        String display = title;
        if (!args.bool("shownamespace", true)) {
            int colon = title.indexOf(':');
            if (colon > 0) {
                display = title.substring(colon + 1);
            }
        }
        int max = args.integer("titlemaxlength", 0);
        if (max > 0 && display.length() > max) {
            display = display.substring(0, max) + "...";
        }
        return display;
    }
}
