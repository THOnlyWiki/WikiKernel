package cc.thonly.wikikernel.wikitext;

import java.util.regex.Pattern;

/**
 * 页面级 CSS（{@code <style>} / TemplateStyles / {@code #CSS}）的安全过滤。
 * <p>
 * {@code <style>} 在 HTML 里是 raw text 元素：字符引用不会被解码，唯一能提前闭合它的
 * 就是字面量 {@code </style}。因此把内容里所有 {@code <} 替换成 CSS 转义 {@code \3C }，
 * 输出里不再出现字面量 {@code <}，从根上杜绝标签注入；同时移除 {@code @import}、
 * {@code expression()}、{@code javascript:} 这类可执行 / 外链注入向量。
 * 其余 CSS 语法（如子选择器 {@code >}）保持不变。
 */
public final class CssSanitizer {

    /** CSS 十六进制转义：{@code \3C} + 一个空格 = 字符「<」。 */
    private static final String LESS_THAN_ESCAPE = "\\3C ";

    private static final Pattern IMPORT = Pattern.compile("@import[^;]*;?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPRESSION = Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVASCRIPT = Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEHAVIOR = Pattern.compile("behavior\\s*:", Pattern.CASE_INSENSITIVE);

    private CssSanitizer() {
    }

    /**
     * 清洗一段 CSS，返回可安全放进 {@code <style>} 的内容。
     * 空输入返回空串。
     */
    public static String sanitize(String css) {
        if (css == null || css.isEmpty()) {
            return "";
        }
        String value = css;
        value = IMPORT.matcher(value).replaceAll("");
        value = EXPRESSION.matcher(value).replaceAll("");
        value = JAVASCRIPT.matcher(value).replaceAll("");
        value = BEHAVIOR.matcher(value).replaceAll("");
        return value.replace("<", LESS_THAN_ESCAPE);
    }

    /**
     * 生成一个 {@code <style>} 块；{@code source} 仅用于调试标记。
     */
    public static String styleBlock(String source, String css) {
        String clean = sanitize(css);
        if (clean.isBlank()) {
            return "";
        }
        String attribute = source == null || source.isBlank() ? ""
                : " data-mw-source=\"" + HtmlSanitizer.escapeAttribute(source) + "\"";
        return "<style" + attribute + ">" + clean + "</style>\n";
    }
}
