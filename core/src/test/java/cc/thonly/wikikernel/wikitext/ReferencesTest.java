package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚注（仿 MediaWiki Cite 扩展）：
 * {@code <ref>…</ref>} 上标编号、{@code <ref name="x">…</ref>} 具名与 {@code <ref name="x"/>} 复用、
 * {@code <references/>}（{@code <reference/>} 别名）列表、错误提示、nowiki 不解析、标题锚点不受污染。
 */
class ReferencesTest {

    private final WikitextRenderer renderer = new WikitextRenderer(
            new WikitextProperties(), title -> Optional.empty());

    @Test
    void rendersNumberedMarkersAndFootnoteList() {
        String html = renderer.render(
                "甲<ref>来源A</ref>乙<ref name=\"b\">来源B</ref>丙<ref name=\"b\"/>丁\n\n<references/>", "测试");

        assertTrue(html.contains(
                "<sup class=\"reference\" id=\"cite_ref-1\"><a href=\"#cite_note-1\">[1]</a></sup>"), html);
        assertTrue(html.contains(
                "<sup class=\"reference\" id=\"cite_ref-2\"><a href=\"#cite_note-2\">[2]</a></sup>"), html);
        // 复用同一编号：id 加后缀、编号不变
        assertTrue(html.contains("id=\"cite_ref-2-2\""), html);
        assertTrue(html.contains("<ol class=\"references\">"), html);
        assertTrue(html.contains("<li id=\"cite_note-1\">"), html);
        assertTrue(html.contains("<li id=\"cite_note-2\">"), html);
        assertTrue(html.contains("来源A"), html);
        assertTrue(html.contains("来源B"), html);
        // 列表里的回链
        assertTrue(html.contains("<a href=\"#cite_ref-2-2\">↑</a>"), html);
        // 列表独占一段，不会被 <p> 包住
        assertFalse(html.contains("<p><ol class=\"references\">"), html);
    }

    @Test
    void supportsForwardNamedRefsAndSingularAlias() {
        String html = renderer.render("前<ref name=\"x\"/>后<ref name=\"x\">内容X</ref>\n\n<reference/>", "测试");

        assertTrue(html.contains("[1]"), html);
        assertTrue(html.contains("<ol class=\"references\">"), html);
        assertTrue(html.contains("内容X"), html);
        assertFalse(html.contains("cite.error"), html);
    }

    @Test
    void duplicateNameWithDifferentContentShowsError() {
        String html = renderer.render("<ref name=\"a\">一</ref><ref name=\"a\">二</ref>\n\n<references/>", "测试");
        assertTrue(html.contains("cite.error.duplicate"), html);
    }

    @Test
    void showsCiteErrors() {
        // 没有 MessageSource（单元测试）时显示翻译键本身，不回落中文
        String html = renderer.render("A<ref/>B<ref name=\"missing\"/>C<ref></ref>D", "测试");
        assertTrue(html.contains("cite.error.unnamedUse"), html);
        assertTrue(html.contains("cite.error.undefined"), html);
        assertTrue(html.contains("cite.error.empty"), html);
    }

    @Test
    void refContentSupportsInlineMarkupAndNowikiIsIgnored() {
        String html = renderer.render(
                "A<ref>''斜体'' [[目标]]</ref>\n\n<references/>\n\n<nowiki><ref>不算</ref></nowiki>", "测试");

        assertTrue(html.contains("<i>斜体</i>"), html);
        assertTrue(html.contains("<a href=\"/wiki/目标\">目标</a>"), html);
        // nowiki 里的 <ref> 不算引用（列表里只有 1 条）
        assertFalse(html.contains("cite_note-2"), html);
        assertTrue(html.contains("&lt;ref&gt;不算&lt;/ref&gt;"), html);
    }

    @Test
    void refsInHeadingsDoNotPolluteAnchorOrToc() {
        String html = renderer.render(
                "== 甲<ref>来源</ref> ==\n\nA\n\n== 乙 ==\n\nB\n\n== 丙 ==\n\nC\n\n== 丁 ==\n\nD\n\n<references/>",
                "测试");

        assertTrue(html.contains("<h2 id=\"甲\">"), html);
        assertTrue(html.contains(
                "<a href=\"#甲\"><span class=\"tocnumber\">1</span> <span class=\"toctext\">甲</span></a>"), html);
        assertTrue(html.contains("甲<sup class=\"reference\""), html);
    }

    @Test
    void multipleReferencesTagsOnlyFirstGetsList() {
        String html = renderer.render("A<ref>x</ref>\n\n<references/>\n\n<references/>", "测试");
        int first = html.indexOf("<ol class=\"references\">");
        assertTrue(first >= 0, html);
        assertFalse(html.indexOf("<ol class=\"references\">", first + 1) >= 0, html);
    }

    @Test
    void unclosedRefStaysLiteralText() {
        String html = renderer.render("<ref>未闭合", "测试");
        assertTrue(html.contains("&lt;ref&gt;未闭合"), html);
    }
}
