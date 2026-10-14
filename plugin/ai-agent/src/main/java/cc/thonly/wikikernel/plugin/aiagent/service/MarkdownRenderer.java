package cc.thonly.wikikernel.plugin.aiagent.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown → 安全 HTML（AI 代理的回复等界面展示用）。
 * <p>
 * 安全策略（AI 输出属于「不可信文本」，与词条正文一样按数据处理）：
 * <ul>
 *     <li>原始 HTML 一律**转义**（{@code escapeHtml}）：AI 写的 &lt;script&gt; / &lt;img onerror&gt; 只会当文本显示；</li>
 *     <li>链接做 URL 清洗（commonmark 的 {@code sanitizeUrls}）：{@code javascript:} / {@code data:} 等不会生成可点击链接；</li>
 *     <li>支持 GFM 表格与 {@code ~~删除线~~}，够 AI 说明 / 列表 / 代码片段用。</li>
 * </ul>
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
    }

    /** 渲染 Markdown（null / 空白 → 空串）。 */
    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return renderer.render(document).trim();
    }
}
