package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次 {@code {{#seo:…}}} / {@code <seo>…</seo>} 调用的解析结果（对应 MediaWiki WikiSEO 的
 * {@code TagParser} + {@code WikiSEO::setMetadata}）。
 * <p>
 * 解析步骤与 {@code TagParser::parseArgs} / {@code expandWikiTextTagArray} 对齐：
 * <ol>
 *     <li>按第一个 {@code =} 拆成 key / value，两边 trim；没有 {@code =} 或 key 为空的项忽略；</li>
 *     <li>值先按当前 frame 展开（WikiKernel 的标签内容由 core 预展开，这里会再展开一次）；</li>
 *     <li>再 strip_tags + html_entity_decode，最后 trim；空值忽略；</li>
 *     <li>{@code description} 归一成 {@code manualDescription} 语义：只有非 {@code auto} / {@code textextracts}
 *     的值才当作「手工描述」（写入 {@code manualDescription=1} 与 {@code description}）。</li>
 * </ol>
 * 参数最终通过 {@link #writeTo} 写入渲染上下文（core 会把它们持久化到页面属性表，相当于 MediaWiki 的 page_props）。
 */
public final class SeoMetadata {

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", "\u00a0"),
            Map.entry("ndash", "\u2013"), Map.entry("mdash", "\u2014"), Map.entry("hellip", "\u2026"),
            Map.entry("laquo", "\u00ab"), Map.entry("raquo", "\u00bb"), Map.entry("times", "\u00d7"),
            Map.entry("middot", "\u00b7"), Map.entry("copy", "\u00a9"), Map.entry("reg", "\u00ae"),
            Map.entry("trade", "\u2122"), Map.entry("deg", "\u00b0"), Map.entry("plusmn", "\u00b1"),
            Map.entry("minus", "\u2212"), Map.entry("divide", "\u00f7"), Map.entry("euro", "\u20ac"),
            Map.entry("pound", "\u00a3"), Map.entry("yen", "\u00a5"), Map.entry("sect", "\u00a7"),
            Map.entry("para", "\u00b6"), Map.entry("bull", "\u2022"), Map.entry("dagger", "\u2020"),
            Map.entry("prime", "\u2032"), Map.entry("larr", "\u2190"), Map.entry("rarr", "\u2192"),
            Map.entry("ldquo", "\u201c"), Map.entry("rdquo", "\u201d"),
            Map.entry("lsquo", "\u2018"), Map.entry("rsquo", "\u2019"));

    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    private final Map<String, String> values;

    private SeoMetadata(Map<String, String> values) {
        this.values = values;
    }

    /** 解析 key=value 原文列表（解析器函数参数 / 标签内容行 / 标签属性都走这里）。 */
    public static SeoMetadata parsePairs(List<String> pairs, UnaryOperator<String> expander) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (pairs != null) {
            for (String pair : pairs) {
                if (pair == null) {
                    continue;
                }
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    // 没有 = 或 key 为空 —— 与 TagParser::parseArgs 一致地忽略
                    continue;
                }
                String key = pair.substring(0, equals).trim();
                if (key.isEmpty()) {
                    continue;
                }
                String value = pair.substring(equals + 1);
                String expanded = expander == null ? value : expander.apply(value);
                String clean = sanitize(expanded);
                if (clean.isEmpty()) {
                    continue;
                }
                parsed.put(key, clean);
            }
        }
        return new SeoMetadata(parsed);
    }

    /**
     * 用一个已经清洗过的参数表构造（合并标签内容与属性时用；值不会再次清洗，
     * 避免把实体解码后的 {@code <} 又当成 HTML 标签剥掉）。
     */
    public static SeoMetadata raw(Map<String, String> params) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String value = entry.getValue();
                if (entry.getKey() != null && !entry.getKey().isBlank() && value != null && !value.isBlank()) {
                    copy.put(entry.getKey().trim(), value.trim());
                }
            }
        }
        return new SeoMetadata(copy);
    }

    /** 原始参数表（键保持原样，未做合法性过滤；保持插入顺序）。 */
    public Map<String, String> values() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * 校验并写入渲染上下文。
     * <p>
     * 合并顺序与原版一致：本页已有属性（低优先级）→ 本次参数（高优先级）；
     * {@code description} 归一成「手工描述」，写入 {@code manualDescription=1} 标记。
     */
    public void writeTo(WikiRenderContext context, SeoValidator validator) {
        Map<String, String> incoming = new LinkedHashMap<>(values);
        Object manual = incoming.remove("description");
        boolean hasManualDescription = false;
        if (manual != null) {
            if (!SeoAutoDescription.isAutoSource(String.valueOf(manual))) {
                incoming.put("description", String.valueOf(manual));
                hasManualDescription = true;
            }
            // auto / textextracts：不写 description，交给 Head 阶段自动生成
        }

        Map<String, String> merged = new LinkedHashMap<>(validator.seoProperties(context.properties()));
        merged.putAll(incoming);

        for (Map.Entry<String, String> entry : validator.validate(merged).entrySet()) {
            context.setProperty(entry.getKey(), entry.getValue());
        }
        if (hasManualDescription) {
            // 与 MediaWiki 一样额外写一个 manualDescription 页面属性，便于区分手工描述与自动描述
            context.setProperty(SeoHead.MANUAL_DESCRIPTION_PROPERTY, "1");
        }
    }

    /** 对应 TagParser::expandWikiTextTagArray 的 strip_tags + html_entity_decode + trim。 */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String text = COMMENT.matcher(value).replaceAll("");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        return text.trim();
    }

    /**
     * 实体解码（本机化实现）：支持十进制 / 十六进制数字实体与常用命名实体。
     * 原版用 PHP 的 {@code html_entity_decode}（完整 HTML5 命名实体表），这里只内置常用实体，见 README。
     */
    public static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(1);
            String replacement = null;
            if (body.startsWith("#")) {
                try {
                    int codePoint = body.startsWith("#x") || body.startsWith("#X")
                            ? Integer.parseInt(body.substring(2), 16)
                            : Integer.parseInt(body.substring(1));
                    if (Character.isValidCodePoint(codePoint)) {
                        replacement = new String(Character.toChars(codePoint));
                    }
                } catch (NumberFormatException ignored) {
                    replacement = null;
                }
            } else {
                replacement = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
            }
            if (replacement == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
