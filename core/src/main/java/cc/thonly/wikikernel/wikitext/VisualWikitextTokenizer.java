package cc.thonly.wikikernel.wikitext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可视化编辑器的 Wikitext 分词器：把「无法安全可视化编辑」的片段替换成私有区占位符
 * （{@code \uE010序号\uE011}），渲染后由前端显示为「源码块」/ 模板预览块；回写时占位符原样还原，
 * 因此模板 / 引用 / 文件 / 分类 / 预格式等复杂内容在可视化编辑中既不会丢失也不会被改写。
 * <ul>
 *     <li>占位：注释、{@code <pre>} / {@code <nowiki>} / 数学 / 代码 / 图库 / 诗歌 / Markdown、{@code <ref>} /
 *     {@code <references/>}、模板与解析器函数 {@code {{…}}}、文件与分类链接、行为开关、签名、
 *     表格（含合并单元格 / 样式 / 标题时）、白名单之外的 HTML 标签；</li>
 *     <li>模板类占位（{@code {{…}}}）带 {@link Token#KIND_TEMPLATE} 类型：编辑器会把它渲染成预览块，
 *     并提供「模板对话框」（参数表单 / 源代码 / 预览，见 {@link VisualEditorService}）；</li>
 *     <li>保留为「原生可编辑」：段落、标题、粗体 / 斜体、内链 / 外链、列表 / 缩进、水平线、{@code <br>}、
 *     简单表格。</li>
 * </ul>
 */
public final class VisualWikitextTokenizer {

    /** 占位符起始 / 结束字符（Unicode 私有区，正常正文不会出现）。 */
    public static final char TOKEN_START = '\uE010';
    public static final char TOKEN_END = '\uE011';

    /** 整块占位：内容与源码原样保存。 */
    private static final List<Pattern> BLOCK_PATTERNS = List.of(
            Pattern.compile("(?si)<pre\\b[^>]*>.*?</pre>"),
            Pattern.compile("(?si)<nowiki\\b[^>]*>.*?</nowiki>"),
            Pattern.compile("(?si)<syntaxhighlight\\b[^>]*>.*?</syntaxhighlight>"),
            Pattern.compile("(?si)<source\\b[^>]*>.*?</source>"),
            Pattern.compile("(?si)<gallery\\b[^>]*>.*?</gallery>"),
            Pattern.compile("(?si)<math\\b[^>]*>.*?</math>"),
            Pattern.compile("(?si)<chem\\b[^>]*>.*?</chem>"),
            Pattern.compile("(?si)<poem\\b[^>]*>.*?</poem>"),
            Pattern.compile("(?si)<markdown\\b[^>]*>.*?</markdown>"),
            Pattern.compile("(?si)<inputbox\\b[^>]*>.*?</inputbox>"),
            // 插件标签（TemplateData 的模板数据 JSON / WikiSEO 的 SEO 参数）：整块占位，不参与可视化编辑
            Pattern.compile("(?si)<templatedata\\b[^>]*>.*?</templatedata>"),
            Pattern.compile("(?si)<seo\\b[^>]*>.*?</seo>"),
            Pattern.compile("(?si)<exclude-banner-page\\s*/\\s*>"),
            Pattern.compile("(?s)\\{\\{\\s*PAGEBANNER\\s*:[^}]*}}"),
            Pattern.compile("(?si)<ref\\b[^>]*>.*?</ref>"),
            Pattern.compile("(?si)<ref\\b[^>]*/>"),
            Pattern.compile("(?si)<references\\b[^>]*/?>"),
            Pattern.compile("(?s)<!--.*?-->"),
            Pattern.compile("\\[\\[(?:文件|File|Image|图像|分类|Category)\\s*:[^\\[\\]]*\\]\\]"),
            Pattern.compile("__(?:TOC|FORCETOC|NOTOC|NOEDITSECTION|NOINDEX|INDEX|"
                    + "NEWSECTIONLINK|NONEWSECTIONLINK|HIDDENCAT)__"),
            Pattern.compile("~{3,5}"));

    /** 保留为原生 HTML 的标签（序列化器都支持）：换行 / 水平线 / 粗体 / 斜体。 */
    private static final List<String> SAFE_TAGS = List.of("br", "hr", "b", "i");

    private static final Pattern TAG = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>");

    /** 表格块里出现这些时无法安全可视化（合并单元格 / 样式 / 标题）→ 整块占位。 */
    private static final Pattern COMPLEX_TABLE =
            Pattern.compile("(?i)colspan|rowspan|style=|align=|\\|\\+");

    private VisualWikitextTokenizer() {
    }

    /**
     * 一个占位片段。
     *
     * @param kind   {@link #KIND_TEMPLATE}（{@code {{…}}}，含解析器函数）或 {@link #KIND_OTHER}
     * @param source 原始源码（回写时原样还原）
     */
    public record Token(String kind, String source) {

        /** 模板 / 解析器函数（可视化编辑器显示为可预览、可打开「模板对话框」的块）。 */
        public static final String KIND_TEMPLATE = "template";
        /** 其它源码块（引用 / 文件 / 分类 / 预格式 / 注释……）。 */
        public static final String KIND_OTHER = "other";
    }

    public record Tokenized(String text, List<Token> tokens) {

        /** 占位片段的源码列表（与占位符序号一一对应）。 */
        public List<String> sources() {
            return tokens.stream().map(Token::source).toList();
        }
    }

    /** 分词：返回替换后的文本与按序号排列的片段（含类型）。 */
    public static Tokenized tokenize(String wikitext) {
        List<Token> tokens = new ArrayList<>();
        String text = wikitext == null ? "" : wikitext;
        for (Pattern pattern : BLOCK_PATTERNS) {
            text = replaceAll(pattern, text, tokens);
        }
        text = extractTemplates(text, tokens);
        text = replaceAll(TAG, text, tokens, true);
        text = extractTables(text, tokens);
        return new Tokenized(text, List.copyOf(tokens));
    }

    private static String replaceAll(Pattern pattern, String text, List<Token> tokens) {
        return replaceAll(pattern, text, tokens, false);
    }

    private static String replaceAll(Pattern pattern, String text, List<Token> tokens, boolean tagsOnly) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            if (tagsOnly && SAFE_TAGS.contains(matcher.group(1).toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.append(text, last, matcher.start());
            out.append(token(tokens, Token.KIND_OTHER, text.substring(matcher.start(), matcher.end())));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** 模板 / 解析器函数：花括号配对扫描（支持嵌套），类型记为 {@link Token#KIND_TEMPLATE}。 */
    private static String extractTemplates(String text, List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("{{", index)) {
                int depth = 0;
                int cursor = index;
                while (cursor < text.length()) {
                    if (text.startsWith("{{", cursor)) {
                        depth++;
                        cursor += 2;
                        continue;
                    }
                    if (text.startsWith("}}", cursor)) {
                        depth--;
                        cursor += 2;
                        if (depth == 0) {
                            break;
                        }
                        continue;
                    }
                    cursor++;
                }
                if (depth == 0) {
                    out.append(token(tokens, Token.KIND_TEMPLATE, text.substring(index, cursor)));
                    index = cursor;
                    continue;
                }
            }
            out.append(text.charAt(index));
            index++;
        }
        return out.toString();
    }

    /** 表格：简单表格保留为原生；含合并单元格 / 样式 / 标题的整块占位。 */
    private static String extractTables(String text, List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("{|", index)) {
                int end = text.indexOf("|}", index);
                if (end >= 0) {
                    String block = text.substring(index, end + 2);
                    if (COMPLEX_TABLE.matcher(block).find()) {
                        out.append(token(tokens, Token.KIND_OTHER, block));
                    } else {
                        out.append(block);
                    }
                    index = end + 2;
                    continue;
                }
            }
            out.append(text.charAt(index));
            index++;
        }
        return out.toString();
    }

    private static String token(List<Token> tokens, String kind, String source) {
        tokens.add(new Token(kind, source));
        return TOKEN_START + Integer.toString(tokens.size() - 1) + TOKEN_END;
    }
}
