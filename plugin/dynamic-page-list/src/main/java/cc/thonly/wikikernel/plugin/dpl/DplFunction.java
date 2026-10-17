package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.service.PageSearchStore;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageQuery;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code {{#dpl:…}}}：DynamicPageList4 的主解析器函数。
 * <p>
 * 本移植覆盖站点实际使用与最常用的查询参数：
 * {@code category} / {@code notcategory} / {@code namespace} / {@code count} / {@code offset} /
 * {@code ordermethod} / {@code order} / {@code titlematch} / {@code titleregexp} /
 * {@code nottitlematch} / {@code nottitleregexp} / {@code redirects}；
 * 输出支持默认列表、{@code mode} 与 {@code format}，以及
 * {@code resultsheader} / {@code resultsfooter} / {@code noresultsheader} / {@code noresultsfooter} /
 * {@code oneresultheader} / {@code oneresultfooter}。
 * <p>
 * {@code cacheperiod} / {@code allowcachedresults} 接受但不单独缓存（页面 HTML 已由 core 的页面缓存处理）。
 */
public final class DplFunction implements ParserFunction {

    public static final String NAME = "dpl";

    private final PageSearchStore searchStore;

    public DplFunction(PageSearchStore searchStore) {
        this.searchStore = searchStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String invoke(ParserFunctionCall call) {
        DplArguments args = DplArguments.parse(call);
        List<PageRow> rows = this.searchStore.query(query(args));
        String header = header(call, args, rows);
        String footer = footer(call, args, rows);

        if (rows.isEmpty() && header.isEmpty() && footer.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!header.isEmpty()) {
            out.append(header).append('\n');
        }
        if (!rows.isEmpty()) {
            out.append(DplFormatter.format(rows, args));
        }
        if (!footer.isEmpty()) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(footer).append('\n');
        }
        return out.toString();
    }

    private static PageQuery query(DplArguments args) {
        String namespace = args.first("namespace", "");
        List<String> categories = clean(args.all("category"));
        List<String> notCategories = clean(args.all("notcategory"));
        String titlePrefix = args.first("titleprefix", "").trim();
        String titleContains = args.first("titlecontains", "").trim();
        String titleRegex = firstNonBlank(args.first("titleregexp"), wildcard(args.first("titlematch")));
        String notRegex = combineNotRegex(args);
        String orderMethod = args.first("ordermethod", "title").trim();
        String order = args.first("order", "ascending").trim().toLowerCase(Locale.ROOT);
        boolean descending = "descending".equals(order) || "desc".equals(order);
        int count = args.integer("count", 10);
        int offset = args.integer("offset", 0);
        String redirects = args.first("redirects", "include").trim().toLowerCase(Locale.ROOT);
        return new PageQuery(namespace, categories, notCategories, titlePrefix, titleContains,
                titleRegex, notRegex, orderMethod, descending, Math.max(0, offset), count,
                redirects.isEmpty() ? "include" : redirects);
    }

    private static String header(ParserFunctionCall call, DplArguments args, List<PageRow> rows) {
        String key;
        if (rows.isEmpty()) {
            key = "noresultsheader";
        } else if (rows.size() == 1 && args.first("oneresultheader") != null) {
            key = "oneresultheader";
        } else {
            key = "resultsheader";
        }
        return expand(call, args.first(key));
    }

    private static String footer(ParserFunctionCall call, DplArguments args, List<PageRow> rows) {
        String key;
        if (rows.isEmpty()) {
            key = "noresultsfooter";
        } else if (rows.size() == 1 && args.first("oneresultfooter") != null) {
            key = "oneresultfooter";
        } else {
            key = "resultsfooter";
        }
        return expand(call, args.first(key));
    }

    private static String expand(ParserFunctionCall call, String raw) {
        if (raw == null) {
            return "";
        }
        String expanded = call.expand(raw);
        if (expanded == null) {
            return "";
        }
        // DPL 约定：结果头尾里的 \n 是换行（页面源码里常写成一个反斜杠 + n）
        return expanded.replace("\\n", "\n").strip();
    }

    private static List<String> clean(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static String combineNotRegex(DplArguments args) {
        String regex = args.first("nottitleregexp");
        String wildcard = wildcard(args.first("nottitlematch"));
        if (regex == null || regex.isBlank()) {
            return wildcard;
        }
        if (wildcard == null || wildcard.isBlank()) {
            return regex;
        }
        return "(?:" + regex + ")|(?:" + wildcard + ")";
    }

    /** DPL 的 {@code %}/{@code _} 通配符 → 正则；普通字符用 {@link Pattern#quote(String)} 包住。 */
    static String wildcard(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%' || c == '_') {
                if (!literal.isEmpty()) {
                    out.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                out.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            out.append(Pattern.quote(literal.toString()));
        }
        return out.append('$').toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
