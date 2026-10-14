package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.web.WikiNamespaces;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wikitext 结构扫描（不渲染）：分类链接、模板调用。
 * <p>
 * 供渲染器（剥离分类链接并收集）与「分类页 / 链入页面 / 页面信息」等反查功能共用。
 * 分类语法与 MediaWiki 一致：{@code [[分类:X]]}、{@code [[分类:X|排序键]]}（{@code Category:} 别名等价）；
 * {@code [[:分类:X]]}（前导冒号）只是普通链接，不归类。
 */
public final class WikiLinkScanner {

    /** 分类命名空间（规范名）。 */
    public static final String CATEGORY_PREFIX = "分类:";

    /** 分类链接（前导冒号不匹配 → 不归类）。 */
    private static final Pattern CATEGORY_LINK = Pattern.compile(
            "\\[\\[\\s*(分类|Category)\\s*:\\s*([^\\[\\]|#]*?)\\s*(?:\\|\\s*([^\\[\\]|]*?))?\\s*\\]\\]",
            Pattern.CASE_INSENSITIVE);

    private WikiLinkScanner() {
    }

    /** 分类引用：规范标题（{@code 分类:X}）+ 可选排序键。 */
    public record CategoryRef(String title, String sortKey) {
    }

    /** 是否是分类命名空间标题（{@code 分类:X} / {@code Category:X}）。 */
    public static boolean isCategoryTitle(String title) {
        return categoryName(title) != null;
    }

    /** 分类命名空间里的名字（不含前缀）；不是分类返回 null。 */
    public static String categoryName(String title) {
        String value = Strings.trimToEmpty(title);
        for (String prefix : List.of(CATEGORY_PREFIX, "Category:")) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return value.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** 规范分类标题：{@code Category:X} / {@code 分类:X} → {@code 分类:X}。 */
    public static String canonicalCategoryTitle(String title) {
        String name = categoryName(title);
        return name == null ? Strings.trimToEmpty(title) : CATEGORY_PREFIX + name;
    }

    /** 收集正文里的分类（同分类去重，排序键以后出现的为准）。 */
    public static List<CategoryRef> categories(String wikitext) {
        List<CategoryRef> refs = new ArrayList<>();
        stripCategories(wikitext, refs);
        return refs;
    }

    /**
     * 移除正文里的分类链接（{@code [[分类:X|键]]} 整段删除），并把收集结果追加到 {@code out}。
     * 渲染器在模板展开后调用：分类链接不会出现在正文里，模板里的分类也会生效。
     */
    public static String stripCategories(String wikitext, List<CategoryRef> out) {
        if (wikitext == null || wikitext.isEmpty()) {
            return "";
        }
        Matcher matcher = CATEGORY_LINK.matcher(wikitext);
        StringBuilder cleaned = new StringBuilder();
        Map<String, String> sortKeys = new LinkedHashMap<>();
        int last = 0;
        while (matcher.find()) {
            cleaned.append(wikitext, last, matcher.start());
            String name = matcher.group(2).trim();
            String sortKey = matcher.group(3) == null ? null : matcher.group(3).trim();
            if (!name.isEmpty()) {
                String title = CATEGORY_PREFIX + name;
                sortKeys.put(title, sortKey); // 重复分类：后者覆盖排序键
            }
            last = matcher.end();
        }
        cleaned.append(wikitext, last, wikitext.length());
        if (out != null) {
            for (Map.Entry<String, String> entry : sortKeys.entrySet()) {
                out.add(new CategoryRef(entry.getKey(), entry.getValue()));
            }
        }
        return cleaned.toString();
    }

    /**
     * 模板调用 → 规范化标题列表（去重、保持出现顺序）：
     * 带命名空间的按原样，否则用第一个模板前缀（如 {@code 模板:}）。
     */
    public static List<String> templateTitles(String wikitext, List<String> prefixes) {
        Set<String> titles = new LinkedHashSet<>();
        for (TemplateCall call : templateCalls(wikitext)) {
            String title = canonicalTemplateTitle(call.name(), prefixes);
            if (!title.isEmpty()) {
                titles.add(title);
            }
        }
        return List.copyOf(titles);
    }

    /**
     * 模板调用名的规范标题（索引存储 / 正查用）：带命名空间时归一到规范前缀（{@code {{Template:X}}} → {@code 模板:X}，
     * 与 {@code {{模板:X}}} 同一条索引），否则用第一个模板前缀（如 {@code 模板:}）。
     */
    public static String canonicalTemplateTitle(String name, List<String> prefixes) {
        String value = Strings.trimToEmpty(name);
        if (value.isEmpty() || prefixes.isEmpty()) {
            return value;
        }
        if (value.indexOf(':') >= 0) {
            return WikiNamespaces.canonicalize(value);
        }
        return prefixes.get(0) + value;
    }

    /**
     * 模板反查候选键（索引查询 / 实时核对用）：查询标题本身 + 去掉已知前缀后的名字 + 每个前缀 + 名字；
     * 例如 {@code 模板:问候} → {@code 模板:问候, 问候, Template:问候}，
     * 覆盖 {@code {{问候}}} / {@code {{模板:问候}}} / {@code {{Template:问候}}} 三种写法。
     */
    public static Set<String> templateIndexKeys(String title, List<String> prefixes) {
        Set<String> keys = new LinkedHashSet<>();
        String value = Strings.trimToEmpty(title);
        if (value.isEmpty()) {
            return keys;
        }
        keys.add(value);
        String name = value;
        for (String prefix : prefixes) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                name = value.substring(prefix.length()).trim();
                break;
            }
        }
        if (!name.isEmpty()) {
            keys.add(name);
            for (String prefix : prefixes) {
                keys.add(prefix + name);
            }
        }
        return keys;
    }

    /**
     * 模板调用 → 反查键集合：raw 名（原样）+ 每个前缀标题；
     * 例如 {@code {{问候|...}}} 且前缀 {@code [模板:, Template:]}
     * → {@code 问候, 模板:问候, Template:问候}，用于反查“哪些页面用了模板:问候”。
     */
    public static Set<String> templateLookupKeys(String wikitext, List<String> prefixes) {
        Set<String> keys = new LinkedHashSet<>();
        for (TemplateCall call : templateCalls(wikitext)) {
            keys.add(call.name());
            if (call.name().indexOf(':') < 0) {
                for (String prefix : prefixes) {
                    keys.add(prefix + call.name());
                }
            }
        }
        return keys;
    }

    /** 模板调用（只保留名字；解析器函数与魔术字已过滤）。 */
    public record TemplateCall(String name) {
    }

    /** 解析正文里的模板调用（保持出现顺序、可含重复）。 */
    public static List<TemplateCall> templateCalls(String wikitext) {
        List<TemplateCall> calls = new ArrayList<>();
        if (wikitext == null || wikitext.isEmpty()) {
            return calls;
        }
        int i = 0;
        while (i < wikitext.length() - 1) {
            int open = wikitext.indexOf("{{", i);
            if (open < 0) {
                break;
            }
            if (open + 2 < wikitext.length() && wikitext.charAt(open + 2) == '{') {
                // {{{参数}}}：跳过整个参数表达式
                int close = wikitext.indexOf("}}}", open + 3);
                i = close < 0 ? wikitext.length() : close + 3;
                continue;
            }
            int close = findExpressionEnd(wikitext, open + 2);
            if (close < 0) {
                break;
            }
            String expression = wikitext.substring(open + 2, close);
            i = close + 2;
            String name = expressionName(expression);
            if (!name.isEmpty()) {
                calls.add(new TemplateCall(name));
            }
        }
        return calls;
    }

    // ==================== 内部实现 ====================

    /** 模板名：第一个 | 之前的部分；跳过解析器函数（#if 等）与魔术字。 */
    private static String expressionName(String expression) {
        int pipe = expression.indexOf('|');
        String name = (pipe < 0 ? expression : expression.substring(0, pipe)).trim();
        if (name.isEmpty() || name.startsWith("#")) {
            return "";
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if ("!".equals(name) || "PAGENAME".equals(upper) || "FULLPAGENAME".equals(upper)
                || "NAMESPACE".equals(upper)) {
            return "";
        }
        return name;
    }

    /** 与渲染器一致的 {{...}} 配对查找（考虑嵌套）。 */
    private static int findExpressionEnd(String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length() - 1) {
            if (text.charAt(i) == '{' && text.charAt(i + 1) == '{') {
                depth++;
                i += 2;
            } else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }
}
