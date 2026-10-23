package cc.thonly.wikikernel.wikitext;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面重定向语法：正文以 {@code #REDIRECT [[目标]]} 或 {@code #重定向 [[目标]]} 开头。
 * <p>
 * 大小写不敏感；冒号可省略；允许 {@code [[目标|显示文字]]}；同一行前后允许空白。
 */
public final class WikiRedirect {

    private static final Pattern PATTERN = Pattern.compile(
            "(?is)^\\s*#\\s*(redirect|重定向|重新導向|重新导向|転送)\\s*:?\\s*\\[\\[\\s*([^\\]|#]+?)\\s*(?:\\|[^\\]]*)?\\]\\]");

    private WikiRedirect() {
    }

    /** 解析重定向目标标题；不是重定向返回空。 */
    public static Optional<String> targetOf(String wikitext) {
        return targetOf(wikitext, "");
    }

    /**
     * 解析重定向目标，并按当前页面解析子页面相对写法
     * （{@code #REDIRECT [[/子页]]} / {@code [[../兄弟]]}）。
     */
    public static Optional<String> targetOf(String wikitext, String pageTitle) {
        if (wikitext == null || wikitext.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(wikitext);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String target = WikiLinkScanner.resolveRelativeTarget(matcher.group(2), pageTitle);
        return target.isEmpty() ? Optional.empty() : Optional.of(target);
    }
}
