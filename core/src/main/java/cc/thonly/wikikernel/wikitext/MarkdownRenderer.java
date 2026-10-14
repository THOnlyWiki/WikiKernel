package cc.thonly.wikikernel.wikitext;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.commonmark.renderer.html.UrlSanitizer;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → 安全 HTML（{@code <markdown>…</markdown>} 标签的内容渲染）。
 * <p>
 * 用仓库里已有的 CommonMark 实现（{@code core/build.gradle} 里的 commonmark + GFM 表格 / 删除线，
 * 与 AI 代理插件用的是同一套依赖，**不新增任何依赖**），其语法集合正好覆盖本标签需要的子集：
 * <ul>
 *     <li>标题 {@code #}…{@code ######}、段落、粗体 / 斜体、行内代码、{@code ~~删除线~~}、
 *     链接 {@code [文字](url)}、自动链接 {@code <https://…>}、图片 {@code ![alt](url)}、
 *     无序 / 有序列表（嵌套不限层级）、引用、水平线、围栏代码块（{@code ```lang}）、GFM 管道表；</li>
 *     <li>换行语义按 CommonMark：单个换行不断段（软换行）；</li>
 *     <li>GFM 表格的单元格内不支持换行 / 表中表（CommonMark 规范即如此）。</li>
 * </ul>
 * 安全策略（{@code <markdown>} 里的内容属于「不可信文本」，与其它用户输入一致）：
 * <ul>
 *     <li>行内 / 块级 HTML 交给 {@link HtmlSanitizer} 白名单（{@code sanitizeTag}）：不在白名单的标签按字面转义，
 *     {@code on*} 事件属性一律被丢掉；</li>
 *     <li>链接 / 图片的 URL 先由 {@link WikiUrlSanitizer} 按 {@link HtmlSanitizer#isSafeUrl} 过滤
 *     （http / https / ftp / mailto / 站内相对路径 / 锚点），再由 {@link #safeUrls} 把失效的元素降级：
 *     {@code javascript:} / {@code data:} 这类地址不会出现在输出里，{@code <a>} 只留文字、{@code <img>} 整个去掉；</li>
 *     <li>围栏代码块复用 {@link SyntaxHighlighter}（其输出本身就是转义后的 HTML）。</li>
 * </ul>
 */
public final class MarkdownRenderer {

    /**
     * 代码块渲染成的 {@code <pre><code class="language-xxx">…</code></pre>}
     * （围栏代码块带语言类；缩进代码块 / 无语言围栏只有 {@code <pre><code>}）。
     */
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "<pre><code(?: class=\"language-([^\"]*)\")?>(.*?)</code></pre>", Pattern.DOTALL);

    /** 渲染结果里带 URL 的元素：成对 {@code <a>…</a>} 或自闭合 {@code <img …>}（CommonMark 自己生成的）。 */
    private static final Pattern URL_ELEMENT = Pattern.compile("<a\\s[^<>]*>.*?</a>|<img\\s[^<>]*/?>", Pattern.DOTALL);

    /** 元素里的 {@code href="…"} / {@code src="…"}（CommonMark 一律用双引号）。 */
    private static final Pattern HREF = Pattern.compile("\\bhref=\"([^\"]*)\"");
    private static final Pattern SRC = Pattern.compile("\\bsrc=\"([^\"]*)\"");

    /** CommonMark 在 {@code sanitizeUrls} 时给外链加的 {@code rel="nofollow"}：这里统一去掉。 */
    private static final Pattern NOFOLLOW = Pattern.compile("\\s+rel=\"nofollow\"");

    /** {@code &quot;} / {@code &#34;} / {@code &amp;} 等实体还原（用于判断 URL 协议）。 */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .sanitizeUrls(true)
                .urlSanitizer(new WikiUrlSanitizer())
                .nodeRendererFactory(new InlineHtmlRendererFactory())
                .build();
    }

    /**
     * 渲染 Markdown（null / 空白 → 空串）。
     * <p>
     * 输入按**原始文本**处理：{@code <markdown>} 里的 wikitext（如 {@code [[首页]]}）不会被解析，
     * 也不需要调用方预先转义——CommonMark 只把输入当 Markdown 解析，文本节点由渲染器转义输出。
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return highlightCodeBlocks(safeUrls(renderer.render(document).trim()));
    }

    /**
     * 把「URL 已被 {@link WikiUrlSanitizer} 清空」的链接 / 图片元素降级处理
     * （{@code <a>} 只留文字、{@code <img>} 整个去掉），顺便去掉 CommonMark 补的 {@code rel="nofollow"}。
     * <p>
     * 为什么不留空 {@code href}：浏览器把空 href 当「当前页」，点一下会刷新页面，体验很怪；
     * 这里统一成 {@code WikitextRenderer} 处理外链的方式。文本里的 {@code <} 已被 CommonMark 转义成
     * {@code &lt;}，所以「尖括号里带 href= / src=」的位置只可能是真正的标签。
     */
    private static String safeUrls(String html) {
        Matcher matcher = URL_ELEMENT.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        while (matcher.find()) {
            String element = matcher.group();
            String safe = cleanElement(element);
            if (safe.equals(element)) {
                continue;
            }
            out.append(html, last, matcher.start()).append(safe);
            last = matcher.end();
        }
        out.append(html, last, html.length());
        return out.toString();
    }

    /**
     * 清洗一个链接 / 图片元素：URL 不安全时降级处理。
     * <ul>
     *     <li>{@code <a href="…">文字</a>}：URL 不安全 → 只留「文字」（不生成 {@code <a>}）；</li>
     *     <li>{@code <img src="…" …>}：URL 不安全 → 整个元素去掉（没有地址的图片没有意义）。</li>
     * </ul>
     * 任何情况下都去掉 CommonMark 补的 {@code rel="nofollow"}。
     */
    private static String cleanElement(String element) {
        boolean anchor = element.startsWith("<a");
        Matcher url = (anchor ? HREF : SRC).matcher(element);
        String destination = url.find() ? url.group(1) : "";
        if (!HtmlSanitizer.isSafeUrl(unescapeHtml(destination))) {
            if (!anchor) {
                return "";
            }
            int contentStart = element.indexOf('>') + 1;
            int contentEnd = element.lastIndexOf("</a>");
            return contentEnd < contentStart ? "" : element.substring(contentStart, contentEnd);
        }
        // 属性之间可能留下两个空格，收成一个（外观问题，不影响安全）
        return NOFOLLOW.matcher(element).replaceAll("").replaceAll("\\s{2,}(?=[^<]*>)", " ");
    }

    /**
     * 把代码块转成项目统一的语法高亮结构
     * （{@code <pre class="syntax" data-lang="java"><code>…</code></pre>}，与 {@code <syntaxhighlight>} 一致）：
     * 围栏代码块按 {@code ```lang} 着色，缩进代码块 / 没有语言的围栏只做转义（{@link SyntaxHighlighter#highlight} 内部处理）。
     */
    private static String highlightCodeBlocks(String html) {
        Matcher matcher = CODE_BLOCK.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        do {
            String language = matcher.group(1) == null ? "" : matcher.group(1);
            String code = unescapeHtml(matcher.group(2));
            // 去掉围栏内容末尾那个换行（与 <syntaxhighlight> 的 stripEdgeBlankLines 观感一致）
            if (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            out.append(html, last, matcher.start());
            out.append("<pre class=\"syntax\"")
                    .append(language.isEmpty() ? ""
                            : " data-lang=\"" + HtmlSanitizer.escapeAttribute(language) + "\"")
                    .append("><code>").append(SyntaxHighlighter.highlight(code, language)).append("</code></pre>");
            last = matcher.end();
        } while (matcher.find());
        out.append(html, last, html.length());
        return out.toString();
    }

    /** 还原 CommonMark 在文本 / 属性里转义的实体（代码块内容、URL 协议判断用）。 */
    private static String unescapeHtml(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current != '&') {
                out.append(current);
                index++;
                continue;
            }
            int end = text.indexOf(';', index + 1);
            String entity = end < 0 ? null : text.substring(index, end + 1);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                out.append(current);
                index++;
                continue;
            }
            out.append(decoded);
            index = end + 1;
        }
        return out.toString();
    }

    /** 解析一个 HTML 实体（不识别时返回 null，保持原样）。 */
    private static String decodeEntity(String entity) {
        if (entity == null) {
            return null;
        }
        String decoded = switch (entity) {
            case "&amp;" -> "&";
            case "&lt;" -> "<";
            case "&gt;" -> ">";
            case "&quot;" -> "\"";
            case "&apos;" -> "'";
            default -> null;
        };
        if (decoded != null) {
            return decoded;
        }
        Matcher numeric = NUMERIC_ENTITY.matcher(entity);
        if (!numeric.matches()) {
            return null;
        }
        try {
            int codePoint = Integer.parseInt(numeric.group(2), "x".equalsIgnoreCase(numeric.group(1)) ? 16 : 10);
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * URL 白名单：把地址交给 {@link HtmlSanitizer#isSafeUrl} 判断，**不安全就换成空串**
     * （CommonMark 随后会跳过这个空属性，于是 {@code <a>} 缺 href、{@code <img>} 缺 src，
     * 再由 {@link #safeUrls} 把前者降级成纯文字、后者整个去掉）。
     * <p>
     * 不用 CommonMark 自带的 {@code DefaultUrlSanitizer} 是因为它只认 http / https / mailto / data：
     * 会把 {@code ftp:} 这类本站允许的地址也清掉，还会给外链补 {@code rel="nofollow"}。
     */
    private static final class WikiUrlSanitizer implements UrlSanitizer {

        @Override
        public String sanitizeLinkUrl(String url) {
            return safe(url);
        }

        @Override
        public String sanitizeImageUrl(String url) {
            return safe(url);
        }

        private static String safe(String url) {
            return url != null && HtmlSanitizer.isSafeUrl(url) ? url : "";
        }
    }

    /** 提供「行内 / 块级 HTML 走白名单」的节点渲染器（默认渲染器会把原始 HTML 原样吐出）。 */
    private static final class InlineHtmlRendererFactory implements HtmlNodeRendererFactory {

        @Override
        public NodeRenderer create(HtmlNodeRendererContext context) {
            return new InlineHtmlRenderer(context);
        }
    }

    /** 行内 HTML（{@code <b>…</b>}）与块级 HTML（{@code <div>…</div>}）统一过 {@link HtmlSanitizer}。 */
    private static final class InlineHtmlRenderer implements NodeRenderer {

        private final HtmlWriter writer;

        private InlineHtmlRenderer(HtmlNodeRendererContext context) {
            this.writer = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(HtmlInline.class, HtmlBlock.class);
        }

        @Override
        public void render(Node node) {
            String literal = node instanceof HtmlBlock block ? block.getLiteral() : ((HtmlInline) node).getLiteral();
            String safe = HtmlSanitizer.sanitizeTag(literal == null ? "" : literal.trim());
            if (safe != null) {
                writer.raw(safe);
                return;
            }
            // 不在白名单（<script> / <img onerror=…> / 注释…）：按字面转义显示，与正文 HTML 处理一致
            writer.text(literal == null ? "" : literal);
        }
    }
}
