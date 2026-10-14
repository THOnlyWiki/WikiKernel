package cc.thonly.wikikernel.wikitext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务端语法高亮（仿 MediaWiki 的 SyntaxHighlight 扩展 / Pygments 观感）：
 * {@code <syntaxhighlight>} / {@code <source>} 标签的代码内容按语言着色成
 * {@code <span class="hl-comment|hl-string|hl-keyword|hl-number|hl-literal">…</span>}，
 * 输出已做 HTML 转义，可以直接放进 {@code <code>}。
 * <p>
 * 语言按关键字表 + 注释 / 字符串 / 数字扫描实现（单遍字符扫描，不用正则回溯）：
 * {@code java / kotlin / javascript(js, ts) / css / json / xml(html) / python / bash(shell) /
 * sql / properties(ini) / lua}；未知语言按纯文本转义。
 */
public final class SyntaxHighlighter {

    /** 超过这个长度就不高亮（只转义），避免极端页面拖慢渲染。 */
    private static final int MAX_LENGTH = 200_000;

    private static final String IDENTIFIER_EXTRA = "_$";

    private static final Map<String, Language> LANGUAGES = Map.ofEntries(
            entry("java", new Language("""
                    abstract assert boolean break byte case catch char class const continue default do double else enum
                    extends final finally float for goto if implements import instanceof int interface long native new
                    package private protected public return short static strictfp super switch synchronized this throw
                    throws transient try void volatile while var yield record sealed permits
                    """, "true false null", List.of("//"), "/*", "*/", false, false, false)),
            entry("kotlin", new Language("""
                    as break class continue do else false for fun if in interface is null object package return super
                    this throw true try typealias typeof val var when while by catch constructor delegate dynamic field
                    file finally get import init param property receiver set setparam where actual abstract annotation
                    companion const crossinline data enum expect external final infix inline inner internal lateinit
                    noinline open operator out override private protected public reified sealed suspend tailrec vararg
                    """, "true false null", List.of("//"), "/*", "*/", false, false, false)),
            entry("javascript", new Language("""
                    async await break case catch class const continue debugger default delete do else export extends
                    finally for function if import in instanceof let new of return static super switch this throw try
                    typeof var void while with yield get set interface type enum implements private public protected
                    readonly namespace declare abstract as satisfies keyof infer
                    """, "true false null undefined NaN Infinity", List.of("//"), "/*", "*/", true, false, false)),
            entry("typescript", new Language("""
                    async await break case catch class const continue debugger default delete do else export extends
                    finally for function if import in instanceof let new of return static super switch this throw try
                    typeof var void while with yield get set interface type enum implements private public protected
                    readonly namespace declare abstract as satisfies keyof infer
                    """, "true false null undefined NaN Infinity", List.of("//"), "/*", "*/", true, false, false)),
            entry("css", new Language("", "", List.of(), "/*", "*/", false, false, false)),
            entry("json", new Language("", "true false null", List.of("//"), "/*", "*/", false, false, false)),
            entry("xml", new Language("", "", List.of(), "<!--", "-->", false, false, true)),
            entry("html", new Language("", "", List.of(), "<!--", "-->", false, false, true)),
            entry("python", new Language("""
                    and as assert async await break class continue def del elif else except finally for from global
                    if import in is lambda nonlocal not or pass raise return try while with yield match case
                    """, "True False None", List.of("#"), null, null, false, false, false, true)),
            entry("bash", new Language("""
                    if then else elif fi for while until do done case esac function in return break continue local
                    export readonly declare source alias unset echo printf cd set trap exit test
                    """, "true false", List.of("#"), null, null, false, false, false)),
            entry("sql", new Language("""
                    select from where insert into values update set delete create alter drop table index view join left
                    right inner outer on group by order having limit offset union all and or not null is as distinct
                    count sum avg min max primary key foreign references default autoincrement exists add column
                    constraint unique
                    """, "true false null", List.of("--"), "/*", "*/", false, true, false)),
            entry("properties", new Language("", "", List.of("#", ";"), null, null, false, false, false)),
            entry("lua", new Language("""
                    and break do else elseif end for function goto if in local nil not or repeat return then until
                    while true false nil
                    """, "true false nil", List.of("--"), "--[[", "]]", false, false, false)),
            entry("wikitext", new Language("", "", List.of(), null, null, false, false, false)),
            entry("markdown", new Language("", "", List.of(), null, null, false, false, false)));

    private SyntaxHighlighter() {
    }

    private static Map.Entry<String, Language> entry(String key, Language language) {
        return Map.entry(key, language);
    }

    /** 高亮代码（返回已转义的 HTML 片段）；语言未知或内容过大时只转义。 */
    public static String highlight(String code, String language) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        Language config = resolve(language);
        if (config == null || code.length() > MAX_LENGTH) {
            return HtmlSanitizer.escapeText(code);
        }
        return new Scanner(code, config).run();
    }

    private static Language resolve(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String id = language.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "js", "jsx", "node" -> LANGUAGES.get("javascript");
            case "ts", "tsx" -> LANGUAGES.get("typescript");
            case "kt", "kts" -> LANGUAGES.get("kotlin");
            case "htm", "xhtml", "svg" -> LANGUAGES.get("xml");
            case "py", "py3", "python3" -> LANGUAGES.get("python");
            case "sh", "shell", "zsh", "console" -> LANGUAGES.get("bash");
            case "ini", "conf", "cnf", "cfg" -> LANGUAGES.get("properties");
            case "md" -> LANGUAGES.get("markdown");
            case "text", "txt", "plaintext", "none", "fallback" -> null;
            default -> LANGUAGES.get(id);
        };
    }

    /**
     * 语言配置。
     *
     * @param keywords            关键字（空白分隔；{@code caseInsensitiveKeywords} 时忽略大小写）
     * @param literals            字面量（true / false / null 等）
     * @param lineComments        行注释前缀（可多个，如 properties 的 # 与 ;）
     * @param blockCommentStart   块注释开始（可空）
     * @param blockCommentEnd     块注释结束（可空）
     * @param backtickStrings     是否支持反引号字符串（JS 模板串）
     * @param caseInsensitiveKeywords 关键字忽略大小写（SQL）
     * @param markup              标记语言（XML / HTML）：标签名作为关键字着色
     * @param tripleQuotes        是否支持三引号字符串（Python）
     */
    private record Language(Set<String> keywords, Set<String> literals, List<String> lineComments,
                            String blockCommentStart, String blockCommentEnd, boolean backtickStrings,
                            boolean caseInsensitiveKeywords, boolean markup, boolean tripleQuotes) {

        Language(String keywords, String literals, List<String> lineComments, String blockCommentStart,
                 String blockCommentEnd, boolean backtickStrings, boolean caseInsensitiveKeywords,
                 boolean markup) {
            this(words(keywords, caseInsensitiveKeywords), words(literals, caseInsensitiveKeywords),
                    lineComments, blockCommentStart, blockCommentEnd, backtickStrings,
                    caseInsensitiveKeywords, markup, false);
        }

        Language(String keywords, String literals, List<String> lineComments, String blockCommentStart,
                 String blockCommentEnd, boolean backtickStrings, boolean caseInsensitiveKeywords,
                 boolean markup, boolean tripleQuotes) {
            this(words(keywords, caseInsensitiveKeywords), words(literals, caseInsensitiveKeywords),
                    lineComments, blockCommentStart, blockCommentEnd, backtickStrings,
                    caseInsensitiveKeywords, markup, tripleQuotes);
        }

        private static Set<String> words(String text, boolean lowerCase) {
            if (text == null || text.isBlank()) {
                return Set.of();
            }
            java.util.Set<String> result = new java.util.TreeSet<>();
            for (String word : text.trim().split("\\s+")) {
                result.add(lowerCase ? word.toLowerCase(Locale.ROOT) : word);
            }
            return result;
        }
    }

    /** 单遍字符扫描：注释 / 字符串 / 数字 / 标识符，其余原样转义。 */
    private static final class Scanner {

        private final String code;
        private final Language language;
        private final StringBuilder out;
        private int index;

        private Scanner(String code, Language language) {
            this.code = code;
            this.language = language;
            this.out = new StringBuilder(code.length() + 64);
        }

        private String run() {
            while (index < code.length()) {
                char c = code.charAt(index);
                if (matchBlockComment()) {
                    continue;
                }
                if (matchLineComment()) {
                    continue;
                }
                if (language.markup() && c == '<' && matchMarkupTag()) {
                    continue;
                }
                if (matchString()) {
                    continue;
                }
                if (Character.isDigit(c)
                        || (c == '.' && index + 1 < code.length() && Character.isDigit(code.charAt(index + 1)))) {
                    matchNumber();
                    continue;
                }
                if (isIdentifierStart(c)) {
                    matchWord();
                    continue;
                }
                appendEscaped(c);
                index++;
            }
            return out.toString();
        }

        private boolean matchBlockComment() {
            String start = language.blockCommentStart();
            String end = language.blockCommentEnd();
            if (start == null || !code.startsWith(start, index)) {
                return false;
            }
            int close = code.indexOf(end, index + start.length());
            int stop = close < 0 ? code.length() : close + end.length();
            wrap("hl-comment", code.substring(index, stop));
            index = stop;
            return true;
        }

        private boolean matchLineComment() {
            for (String prefix : language.lineComments()) {
                if (code.startsWith(prefix, index)) {
                    int stop = code.indexOf('\n', index);
                    if (stop < 0) {
                        stop = code.length();
                    }
                    wrap("hl-comment", code.substring(index, stop));
                    index = stop;
                    return true;
                }
            }
            return false;
        }

        /** XML / HTML 标签名（{@code <tag} / {@code </tag}）。 */
        private boolean matchMarkupTag() {
            int cursor = index + 1;
            if (cursor < code.length() && code.charAt(cursor) == '/') {
                cursor++;
            }
            if (cursor >= code.length() || !isIdentifierStart(code.charAt(cursor))) {
                return false;
            }
            int start = cursor;
            while (cursor < code.length() && isIdentifierPart(code.charAt(cursor))) {
                cursor++;
            }
            out.append("&lt;");
            boolean closing = index + 1 < code.length() && code.charAt(index + 1) == '/';
            if (closing) {
                out.append('/');
            }
            wrap("hl-keyword", code.substring(start, cursor));
            index = cursor;
            return true;
        }

        private boolean matchString() {
            char quote = code.charAt(index);
            boolean string = quote == '\'' || quote == '"' || (quote == '`' && language.backtickStrings());
            if (!string) {
                return false;
            }
            int cursor = index + 1;
            if (language.tripleQuotes() && code.startsWith(String.valueOf(quote).repeat(3), index)) {
                String triple = String.valueOf(quote).repeat(3);
                int close = code.indexOf(triple, index + 3);
                int stop = close < 0 ? code.length() : close + 3;
                wrap("hl-string", code.substring(index, stop));
                index = stop;
                return true;
            }
            while (cursor < code.length()) {
                char current = code.charAt(cursor);
                if (current == '\\') {
                    cursor += 2;
                    continue;
                }
                cursor++;
                if (current == quote) {
                    break;
                }
            }
            int stop = Math.min(cursor, code.length());
            wrap("hl-string", code.substring(index, stop));
            index = stop;
            return true;
        }

        private void matchNumber() {
            int cursor = index;
            while (cursor < code.length()) {
                char current = code.charAt(cursor);
                if (Character.isLetterOrDigit(current) || current == '.' || current == '_'
                        || ((current == '+' || current == '-') && cursor > index
                        && (code.charAt(cursor - 1) == 'e' || code.charAt(cursor - 1) == 'E'))) {
                    cursor++;
                    continue;
                }
                break;
            }
            wrap("hl-number", code.substring(index, cursor));
            index = cursor;
        }

        private void matchWord() {
            int cursor = index + 1;
            while (cursor < code.length() && isIdentifierPart(code.charAt(cursor))) {
                cursor++;
            }
            String word = code.substring(index, cursor);
            index = cursor;
            String lookup = language.caseInsensitiveKeywords() ? word.toLowerCase(Locale.ROOT) : word;
            if (language.keywords().contains(lookup)) {
                wrap("hl-keyword", word);
            } else if (language.literals().contains(lookup)) {
                wrap("hl-literal", word);
            } else {
                out.append(escape(word));
            }
        }

        private void wrap(String cssClass, String text) {
            out.append("<span class=\"").append(cssClass).append("\">").append(escape(text)).append("</span>");
        }

        private void appendEscaped(char c) {
            out.append(escape(String.valueOf(c)));
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || IDENTIFIER_EXTRA.indexOf(c) >= 0;
        }

        private static String escape(String text) {
            StringBuilder escaped = new StringBuilder(text.length() + 16);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '&' -> escaped.append("&amp;");
                    case '<' -> escaped.append("&lt;");
                    case '>' -> escaped.append("&gt;");
                    case '"' -> escaped.append("&quot;");
                    default -> escaped.append(c);
                }
            }
            return escaped.toString();
        }
    }
}
