package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面标题规范化与校验（仿 MediaWiki 的标题规则）：
 * <ul>
 *     <li><b>下划线等价于空格</b>：{@code Java_开发教程} 与 {@code Java 开发教程} 是同一个标题；</li>
 *     <li>连续的下划线 / 空格 / 制表符 / 不换行空格 / 全角空格折叠为一个空格，去掉首尾空白；</li>
 *     <li>开头的冒号表示主命名空间（{@code :Foo} → {@code Foo}）；</li>
 *     <li><b>命名空间别名归一</b>（{@link WikiNamespaces#canonicalize(String)}）：前缀不区分大小写地换成规范
 *         （本地化）前缀，{@code User:甲} / {@code user:甲} → {@code 用户:甲}、{@code Template:乙} → {@code 模板:乙}、
 *         {@code Image:丙.png} → {@code 文件:丙.png}、{@code Special:版本} → {@code 特殊:版本}；</li>
 *     <li><b>首字母自动大写</b>（{@code $wgCapitalLinks}，默认开，{@code wikikernel.title.capitalize-first-letter} 可关）：
 *         命名空间后的第一个字符大写，如 {@code java 开发教程} → {@code Java 开发教程}、{@code 模板:infobox} → {@code 模板:Infobox}；
 *         子页面的后续片段不自动大写（{@code foo/bar} → {@code Foo/bar}）；</li>
 *     <li><b>非法字符校验</b>：{@code # < > [ ] | { }}、控制字符、{@code %XX}、HTML 实体、{@code ~~~}、
 *         {@code .} / {@code ..} 路径片段、超过 255 字节、命名空间后为空、剩余的前导冒号（仿 MediaWiki 的
 *         {@code title-invalid-*} 错误）。</li>
 * </ul>
 * 所有按标题的查询 / 保存 / 权限判定都应先经过这里（子页面分隔符 {@code /} 保留不动）。
 */
public final class WikiTitles {

    /** 非法字符（仿 MediaWiki 默认 $wgLegalTitleChars 的补集）。 */
    private static final String ILLEGAL_CHARS = "#<>[]|{}";

    /** URL 百分号编码序列（会破坏标题的往返一致性）。 */
    private static final Pattern PERCENT_ENCODING = Pattern.compile("%[0-9A-Fa-f]{2}");

    /** XML / HTML 字符实体。 */
    private static final Pattern CHARACTER_ENTITY = Pattern.compile("&[A-Za-z0-9\\u0080-\\uFFFF]+;");

    /** {@code .} / {@code ..} 相对路径片段（与子页面语法冲突，浏览器也常无法访问）。 */
    private static final Pattern RELATIVE_SEGMENT = Pattern.compile("(^|/)\\.\\.?($|/)");

    /** MediaWiki 的标题长度上限（字节）。 */
    public static final int MAX_TITLE_BYTES = 255;

    /** 首字母自动大写（仿 {@code $wgCapitalLinks}，默认开；{@code wikikernel.title.capitalize-first-letter} 可关）。 */
    private static volatile boolean capitalizeFirstLetter = true;

    private WikiTitles() {
    }

    /** 由配置（{@link WikiTitleProperties}）在启动时调用。 */
    public static void setCapitalizeFirstLetter(boolean value) {
        capitalizeFirstLetter = value;
    }

    public static boolean isCapitalizeFirstLetter() {
        return capitalizeFirstLetter;
    }

    /** 标题问题的种类：{@code code} 对应 MediaWiki 的 title-invalid-* 错误，{@code detail} 是命中的字符 / 片段。 */
    public record TitleError(String code, String messageKey, String detail) {
    }

    /** 规范化标题；空白输入返回空串（由调用方决定是否报错）。 */
    public static String normalize(String title) {
        String value = WikiNamespaces.canonicalize(prepare(title));
        if (value.isEmpty()) {
            return "";
        }
        if (capitalizeFirstLetter) {
            value = capitalizeAfterNamespace(value);
        }
        return value;
    }

    /**
     * 校验标题（传入原始输入即可，内部做同样的规范化）。
     * 返回第一个问题；没有问题时为空。
     */
    public static Optional<TitleError> validate(String title) {
        String value = WikiNamespaces.canonicalize(prepare(title));
        if (value.isEmpty()) {
            return Optional.of(new TitleError("empty", "error.titleEmpty", ""));
        }
        // 开头的冒号只允许一个（表示主命名空间）：还剩下说明用户写了「::Foo」这类标题
        if (value.startsWith(":")) {
            return Optional.of(new TitleError("leading-colon", "error.titleLeadingColon", ""));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || ILLEGAL_CHARS.indexOf(c) >= 0) {
                return Optional.of(new TitleError("characters", "error.titleInvalidChars", String.valueOf(c)));
            }
        }
        Matcher percent = PERCENT_ENCODING.matcher(value);
        if (percent.find()) {
            return Optional.of(new TitleError("characters", "error.titleInvalidChars", percent.group()));
        }
        Matcher entity = CHARACTER_ENTITY.matcher(value);
        if (entity.find()) {
            return Optional.of(new TitleError("characters", "error.titleInvalidChars", entity.group()));
        }
        if (RELATIVE_SEGMENT.matcher(value).find()) {
            return Optional.of(new TitleError("relative", "error.titleRelative", ""));
        }
        if (value.contains("~~~")) {
            return Optional.of(new TitleError("magic-tilde", "error.titleMagicTilde", ""));
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TITLE_BYTES) {
            return Optional.of(new TitleError("too-long", "error.titleTooLong", String.valueOf(MAX_TITLE_BYTES)));
        }
        if (namespaceOnly(value)) {
            return Optional.of(new TitleError("empty", "error.titleNamespaceRequired", ""));
        }
        return Optional.empty();
    }

    /** 首字符大写（只影响一个码点，其余原样；文件扩展名、子页面片段不受影响）。 */
    public static String capitalizeFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int codePoint = value.codePointAt(0);
        int upper = Character.toUpperCase(codePoint);
        if (upper == codePoint) {
            return value;
        }
        int charCount = Character.charCount(codePoint);
        return new StringBuilder(value.length()).appendCodePoint(upper).append(value, charCount, value.length()).toString();
    }

    /** 按当前配置决定是否大写首字符（文件名等使用）。 */
    public static String capitalizeFirstIfEnabled(String value) {
        return capitalizeFirstLetter ? capitalizeFirst(value) : value;
    }

    /** 规范化前的准备：折叠空白 + 去掉 Unicode 双向文本控制符 + 去掉一个开头的冒号。 */
    private static String prepare(String title) {
        String value = stripBidiControls(foldWhitespace(Strings.trimToEmpty(title)));
        if (value.startsWith(":")) {
            value = foldWhitespace(value.substring(1));
        }
        return value;
    }

    /** 命名空间后的第一个字符大写；没有命名空间时大写整个标题的第一个字符。 */
    private static String capitalizeAfterNamespace(String value) {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (WikiNamespaces.MAIN.equals(namespace)) {
                continue;
            }
            for (String prefix : namespace.prefixes()) {
                if (value.length() > prefix.length()
                        && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return value.substring(0, prefix.length())
                            + capitalizeFirst(value.substring(prefix.length()));
                }
            }
        }
        return capitalizeFirst(value);
    }

    /** 标题是否只有命名空间前缀（如「模板:」）。 */
    private static boolean namespaceOnly(String value) {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (WikiNamespaces.MAIN.equals(namespace)) {
                continue;
            }
            for (String prefix : namespace.prefixes()) {
                if (value.length() == prefix.length()
                        && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripBidiControls(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u200E' || c == '\u200F' || (c >= '\u202A' && c <= '\u202E')) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String foldWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isWhitespace(c)) {
                if (!previousSpace) {
                    out.append(' ');
                    previousSpace = true;
                }
            } else {
                out.append(c);
                previousSpace = false;
            }
        }
        return out.toString().trim();
    }

    /** MediaWiki 会把下划线以及各种 Unicode 空白折叠成空格（含 NBSP / 全角空格）。 */
    private static boolean isWhitespace(char c) {
        return c == '_' || c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\u00A0' || c == '\u1680' || c == '\u180E' || c == '\u202F' || c == '\u205F'
                || c == '\u2028' || c == '\u2029' || c == '\u3000'
                || (c >= '\u2000' && c <= '\u200A');
    }
}
