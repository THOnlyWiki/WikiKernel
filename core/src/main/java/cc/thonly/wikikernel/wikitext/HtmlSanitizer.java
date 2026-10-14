package cc.thonly.wikikernel.wikitext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 允许的 HTML 标签白名单 + 属性过滤：wikitext 内嵌的 HTML 安全由这里保证。
 * <p>
 * 允许常用格式化标签、表格、以及 {@code <br>}/{@code <hr>}/{@code <iframe>}；
 * 其它标签不通过（由渲染器按纯文本转义输出），{@code onclick} 等事件属性和
 * {@code javascript:} 之类的 URL 一律拒绝。
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {
    }

    private static final Pattern TAG = Pattern.compile(
            "^<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^<>]*?)?)\\s*(/?)\\s*>$");
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final Pattern DANGEROUS_STYLE = Pattern.compile(
            "expression\\s*\\(|javascript\\s*:|behavior\\s*:|@import|url\\s*\\(\\s*[\"']?\\s*(?!https?|/|#)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> VOID_TAGS = Set.of("br", "hr");
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "b", "strong", "i", "em", "u", "s", "strike", "del", "ins", "mark",
            "code", "kbd", "samp", "var", "tt", "big", "small", "sub", "sup",
            "span", "div", "p", "br", "hr", "blockquote", "center", "font",
            "ul", "ol", "li", "dl", "dt", "dd",
            "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td",
            "pre", "a", "img", "iframe", "ruby", "rt", "rp", "abbr", "cite", "q", "time");

    private static final Set<String> GLOBAL_ATTRS = Set.of("class", "id", "title", "dir", "lang", "style");
    private static final Map<String, Set<String>> TAG_ATTRS = tagAttributes();

    private static Map<String, Set<String>> tagAttributes() {
        Map<String, Set<String>> attrs = new LinkedHashMap<>();
        attrs.put("a", Set.of("href", "target", "rel"));
        attrs.put("img", Set.of("src", "alt", "width", "height"));
        attrs.put("iframe", Set.of("src", "width", "height", "frameborder", "allowfullscreen",
                "allow", "loading", "referrerpolicy", "sandbox", "scrolling"));
        attrs.put("table", Set.of("border", "cellpadding", "cellspacing", "width", "summary"));
        attrs.put("tr", Set.of("align", "valign"));
        attrs.put("td", Set.of("colspan", "rowspan", "align", "valign", "width", "height"));
        attrs.put("th", Set.of("colspan", "rowspan", "align", "valign", "width", "height", "scope"));
        attrs.put("ol", Set.of("start", "type", "reversed"));
        attrs.put("font", Set.of("color", "size", "face"));
        return attrs;
    }

    /**
     * 清洗一个 HTML 标签。
     *
     * @return 安全的 HTML 标签文本；不在白名单时返回 {@code null}
     */
    public static String sanitizeTag(String rawTag) {
        Matcher matcher = TAG.matcher(rawTag);
        if (!matcher.matches()) {
            return null;
        }
        boolean closing = !matcher.group(1).isEmpty();
        String name = matcher.group(2).toLowerCase(Locale.ROOT);
        if (!ALLOWED_TAGS.contains(name)) {
            return null;
        }
        if (closing) {
            return "</" + name + ">";
        }
        if (VOID_TAGS.contains(name)) {
            return "<" + name + ">";
        }

        StringBuilder out = new StringBuilder("<").append(name);
        String attributeText = matcher.group(3) == null ? "" : matcher.group(3);
        Matcher attribute = ATTR.matcher(attributeText);
        while (attribute.find()) {
            appendAttribute(out, name, attribute);
        }
        out.append('>');
        return out.toString();
    }

    private static void appendAttribute(StringBuilder out, String tag, Matcher attribute) {
        String name = attribute.group(1).toLowerCase(Locale.ROOT);
        String value = attribute.group(2) != null ? attribute.group(2)
                : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
        boolean allowed = GLOBAL_ATTRS.contains(name) || TAG_ATTRS.getOrDefault(tag, Set.of()).contains(name);
        if (!allowed) {
            return;
        }
        if (value == null) {
            if ("allowfullscreen".equals(name) || "reversed".equals(name) || "scrolling".equals(name)) {
                out.append(' ').append(name);
            }
            return;
        }
        if (("href".equals(name) || "src".equals(name)) && !isSafeUrl(value)) {
            return;
        }
        if ("style".equals(name) && !isSafeStyle(value)) {
            return;
        }
        if (("colspan".equals(name) || "rowspan".equals(name)) && !value.matches("\\d{1,2}")) {
            return;
        }
        out.append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"');
    }

    public static boolean isSafeUrl(String url) {
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("mailto:")
                || value.startsWith("ftp://") || value.startsWith("/") || value.startsWith("#") || value.startsWith("./");
    }

    public static boolean isSafeStyle(String style) {
        return !DANGEROUS_STYLE.matcher(style).find();
    }

    /** 文本转义（用于正文，不用于属性）。 */
    public static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 属性值转义。 */
    public static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
