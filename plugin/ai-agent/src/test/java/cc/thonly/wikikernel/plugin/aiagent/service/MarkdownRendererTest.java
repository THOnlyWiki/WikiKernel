package cc.thonly.wikikernel.plugin.aiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 回复的 Markdown 渲染（迁移前是 core 的 {@code MarkdownRendererTest}）：
 * 粗体 / 列表 / 代码 / 表格 / 删除线 / 引用，原始 HTML 转义，{@code javascript:} 链接被清洗
 * ——AI 输出属于「不可信文本」，与词条正文一样按数据处理。
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersBoldListsCodeTablesStrikethroughAndQuotes() {
        String html = renderer.render("""
                **粗体** 与 `code`

                - 甲
                - 乙

                | 列 | 值 |
                | --- | --- |
                | a | b |

                ~~删除~~ 与

                > 引用
                """);

        assertTrue(html.contains("<strong>粗体</strong>"), html);
        assertTrue(html.contains("<code>code</code>"), html);
        assertTrue(html.contains("<li>甲</li>"), html);
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<del>删除</del>"), html);
        assertTrue(html.contains("<blockquote>"), html);
    }

    @Test
    void escapesRawHtml() {
        String html = renderer.render("正常文字 <script>alert(1)</script> <img src=x onerror=alert(2)>");

        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img"), html);
        // 原样转义成文本（不会成为可执行标签）
        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.contains("&lt;img src=x"), html);
    }

    @Test
    void sanitizesDangerousLinks() {
        String html = renderer.render("[点我](javascript:alert(1))");

        assertFalse(html.contains("href=\"javascript:"), html);
        assertTrue(html.contains("点我"), html);
    }

    @Test
    void blankInputRendersEmpty() {
        assertTrue(renderer.render(null).isEmpty());
        assertTrue(renderer.render("   ").isEmpty());
    }
}
