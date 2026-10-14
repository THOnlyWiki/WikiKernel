package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录（TOC，仿 MediaWiki）：
 * 竖排编号（1 / 1.1 / 1.1.1）、插在第一个标题之前、可折叠；
 * 默认 ≥4 个标题自动显示，{@code __TOC__} / {@code __FORCETOC__} 强制，{@code __NOTOC__} 关闭；
 * 标题有唯一锚点（中文可用、重复的加 _2）。
 */
class TableOfContentsTest {

    private final WikitextRenderer renderer = new WikitextRenderer(
            new WikitextProperties(), title -> Optional.empty());

    @Test
    void showsNumberedTocWhenAtLeastFourHeadings() {
        String html = renderer.render(
                "== A ==\n=== AA ===\n== B ==\n=== BB ===\n==== BBB ====", "测试");

        assertTrue(html.contains("id=\"toc\""), html);
        // 标题带锚点
        assertTrue(html.contains("<h2 id=\"A\">A</h2>"), html);
        assertTrue(html.contains("id=\"AA\""), html);
        assertTrue(html.contains("id=\"B\""), html);
        assertTrue(html.contains("id=\"BB\""), html);
        assertTrue(html.contains("id=\"BBB\""), html);
        // 编号：1 / 1.1 / 2 / 2.1 / 2.1.1
        assertTrue(html.contains("<span class=\"tocnumber\">1</span>"), html);
        assertTrue(html.contains("<span class=\"tocnumber\">1.1</span>"), html);
        assertTrue(html.contains("<span class=\"tocnumber\">2</span>"), html);
        assertTrue(html.contains("<span class=\"tocnumber\">2.1</span>"), html);
        assertTrue(html.contains("<span class=\"tocnumber\">2.1.1</span>"), html);
        // 目录在第一个标题之前
        assertTrue(html.indexOf("id=\"toc\"") < html.indexOf("<h2 id=\"A\">"), html);
        // 折叠开关
        assertTrue(html.contains("toc-hide-label"), html);
        assertTrue(html.contains("toc-show-label"), html);
    }

    @Test
    void fewHeadingsHideTocUnlessMagicWord() {
        String few = renderer.render("== A ==\n== B ==\n=== BB ===", "测试");
        assertFalse(few.contains("id=\"toc\""), few);

        // __TOC__ 强制显示，魔术字本身从正文剥离
        String forced = renderer.render("__TOC__\n== A ==\n== B ==\n=== BB ===", "测试");
        assertTrue(forced.contains("id=\"toc\""), forced);
        assertFalse(forced.contains("__TOC__"), forced);

        // __FORCETOC__ 同样强制
        assertTrue(renderer.render("__FORCETOC__\n== A ==\n== B ==", "测试").contains("id=\"toc\""));

        // __NOTOC__ 关闭（即使 ≥4 个标题）且被剥离
        String none = renderer.render("__NOTOC__\n== A ==\n== B ==\n== C ==\n== D ==", "测试");
        assertFalse(none.contains("id=\"toc\""), none);
        assertFalse(none.contains("__NOTOC__"), none);
    }

    @Test
    void anchorsAreUniqueAndSupportChinese() {
        String html = renderer.render(
                "== 中文标题 ==\n== 中文标题 ==\n== A.B ==\n== 中文标题 ==\n== 中文标题 ==", "测试");

        assertTrue(html.contains("id=\"中文标题\""), html);
        assertTrue(html.contains("id=\"中文标题_2\""), html);
        assertTrue(html.contains("id=\"中文标题_3\""), html);
        assertTrue(html.contains("href=\"#中文标题_2\""), html);
        assertTrue(html.contains("id=\"A.B\""), html);
    }
}
