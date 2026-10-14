package cc.thonly.wikikernel.wikitext;

/**
 * 内联 {@code <style>} 的安全过滤。
 * <p>
 * {@code <style>} 在 HTML 里是 raw text 元素：字符引用不会被解码，唯一能提前闭合它的
 * 就是字面量 {@code </style}。因此把内容里所有 {@code <} 替换成 CSS 转义 {@code \3C }，
 * 输出里不再出现字面量 {@code <}，从根上杜绝标签注入；
 * 其余 CSS 语法（如子选择器 {@code >}）保持不变，CSS 字符串里的 {@code <} 语义也等价。
 */
public final class CssSanitizer {

    /** CSS 十六进制转义：{@code \3C} + 一个空格 = 字符「<」。 */
    private static final String LESS_THAN_ESCAPE = "\\3C ";

    private CssSanitizer() {
    }

    public static String sanitize(String css) {
        if (css == null || css.isEmpty()) {
            return "";
        }
        return css.replace("<", LESS_THAN_ESCAPE);
    }
}
