package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code <markdown>} 标签的 Markdown 子集渲染（CommonMark + GFM 表格 / 删除线）：
 * 标题 / 行内标记 / 列表（嵌套）/ 引用 / 代码块（复用语法高亮）/ 表格 / 链接 / 图片，
 * 行内 HTML 走 {@link HtmlSanitizer} 白名单，{@code <script>} 与 {@code javascript:} 一律不可通过。
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersHeadingsInlineMarkupAndHorizontalRule() {
        String html = renderer.render("""
                # 一级标题

                这是**粗体**、*斜体*、`行内代码` 与 ~~删除线~~。

                ---

                ### 三级标题
                """);

        assertTrue(html.contains("<h1>一级标题</h1>"), html);
        assertTrue(html.contains("<h3>三级标题</h3>"), html);
        assertTrue(html.contains("<strong>粗体</strong>"), html);
        assertTrue(html.contains("<em>斜体</em>"), html);
        assertTrue(html.contains("<code>行内代码</code>"), html);
        assertTrue(html.contains("<del>删除线</del>"), html);
        assertTrue(html.contains("<hr />"), html);
    }

    @Test
    void keepsSingleNewlineInsideParagraph() {
        // CommonMark：单个换行不断段（软换行）
        String html = renderer.render("第一行\n第二行");
        assertEquals("<p>第一行\n第二行</p>", html);
    }

    @Test
    void rendersListsQuotesAndNestedList() {
        String html = renderer.render("""
                - 甲
                - 乙
                  1. 乙一
                  2. 乙二

                > 引用一行

                1. 一
                2. 二
                """);

        assertTrue(html.contains("<ul>"), html);
        assertTrue(html.contains("<li>甲</li>"), html);
        assertTrue(html.contains("<ol>"), html);
        assertTrue(html.contains("<li>乙一</li>"), html);
        assertTrue(html.contains("<blockquote>"), html);
        assertTrue(html.contains("引用一行"), html);
    }

    @Test
    void highlightsFencedCodeBlockByLanguage() {
        String html = renderer.render("""
                ```java
                // 注释
                int x = 42;
                ```
                """);

        assertTrue(html.contains("<pre class=\"syntax\" data-lang=\"java\">"), html);
        assertTrue(html.contains("<span class=\"hl-comment\">// 注释</span>"), html);
        assertTrue(html.contains("<span class=\"hl-keyword\">int</span>"), html);
        assertTrue(html.contains("<span class=\"hl-number\">42</span>"), html);
        assertFalse(html.contains("language-java"), html);

        // 没有语言：退化为普通转义，仍用项目的语法高亮结构
        String plain = renderer.render("```\na < b\n```");
        assertTrue(plain.contains("<pre class=\"syntax\"><code>a &lt; b</code></pre>"), plain);
    }

    @Test
    void rendersGfmTable() {
        String html = renderer.render("""
                | 名称 | 值 |
                | --- | --- |
                | 甲 | 1 |
                | 乙 | 2 |
                """);

        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<th>名称</th>"), html);
        assertTrue(html.contains("<td>甲</td>"), html);
        assertTrue(html.contains("<td>2</td>"), html);
    }

    @Test
    void rendersLinksAutolinksAndImages() {
        String html = renderer.render("[文字](https://example.com/a) 与 <https://example.com/b> 与 ![图](https://example.com/c.png)");

        assertTrue(html.contains("<a href=\"https://example.com/a\">文字</a>"), html);
        assertTrue(html.contains("<a href=\"https://example.com/b\">https://example.com/b</a>"), html);
        assertTrue(html.contains("<img src=\"https://example.com/c.png\" alt=\"图\" />"), html);
    }

    @Test
    void escapesScriptAndStripsEventHandlers() {
        String html = renderer.render("<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>");

        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("</script>"), html);
        assertFalse(html.contains("onerror"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
    }

    @Test
    void dropsDangerousLinkAndImageUrls() {
        // javascript: 一律不生成 <a> / <img>（链接只留可见文字），与 WikitextRenderer 的外链处理一致
        String link = renderer.render("[点我](javascript:alert(1))");
        assertFalse(link.contains("javascript:"), link);
        assertFalse(link.contains("<a"), link);
        assertTrue(link.contains("点我"), link);

        String image = renderer.render("![图](javascript:alert(1))");
        assertFalse(image.contains("javascript:"), image);
        assertFalse(image.contains("<img"), image);

        // 白名单内的协议正常保留（含 ftp:，比 CommonMark 自带规则更宽）
        assertTrue(renderer.render("[站内](/wiki/首页)").contains("href=\"/wiki/首页\""),
                renderer.render("[站内](/wiki/首页)"));
        assertTrue(renderer.render("[邮件](mailto:a@b.c)").contains("href=\"mailto:a@b.c\""),
                renderer.render("[邮件](mailto:a@b.c)"));
        assertTrue(renderer.render("[ftp](ftp://h/x.zip)").contains("href=\"ftp://h/x.zip\""),
                renderer.render("[ftp](ftp://h/x.zip)"));
    }

    @Test
    void keepsWhitelistedInlineHtmlAndEscapesWikitext() {
        String html = renderer.render("行内 <b>粗体</b> 与 <span class=\"x\">片段</span>");

        assertTrue(html.contains("<b>粗体</b>"), html);
        assertTrue(html.contains("<span class=\"x\">片段</span>"), html);
        // wikitext 按原始文本处理：[[首页]] 原样出现在输出里
        assertTrue(renderer.render("[[首页]] 不是链接").contains("[[首页]]"),
                renderer.render("[[首页]] 不是链接"));
    }

    @Test
    void blankInputRendersEmpty() {
        assertTrue(renderer.render(null).isEmpty());
        assertTrue(renderer.render("   ").isEmpty());
    }
}
