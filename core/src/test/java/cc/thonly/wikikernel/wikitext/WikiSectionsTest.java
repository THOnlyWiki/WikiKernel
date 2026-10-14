package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落切分工具（仿 MediaWiki 的 section 编辑）：段号、子标题归属、提取 / 合并只动目标段、
 * CRLF 保留、pre / nowiki 里的假标题不算段。
 */
class WikiSectionsTest {

    private static final String PAGE = "导语\n\n== 甲 ==\n甲1\n\n=== 甲一 ===\n甲一内容\n\n== 乙 ==\n乙1\n";

    @Test
    void parsesAndExtractsSections() {
        List<WikiSections.Section> sections = WikiSections.parse(PAGE);
        assertEquals(3, sections.size());
        assertEquals(1, sections.get(0).index());
        assertEquals(2, sections.get(0).level());
        assertEquals("甲", sections.get(0).heading());
        assertEquals(3, sections.get(1).level());
        assertEquals("甲一", sections.get(1).heading());
        assertEquals("乙", sections.get(2).heading());

        // 第 1 段包含它的子标题段，直到下一个同级标题
        assertEquals("== 甲 ==\n甲1\n\n=== 甲一 ===\n甲一内容\n\n", WikiSections.extract(PAGE, 1));
        assertEquals("=== 甲一 ===\n甲一内容\n\n", WikiSections.extract(PAGE, 2));
        assertEquals("== 乙 ==\n乙1\n", WikiSections.extract(PAGE, 3));
        assertEquals("导语\n\n", WikiSections.extract(PAGE, 0));
        assertNull(WikiSections.extract(PAGE, 9));
    }

    @Test
    void replacesOnlyTargetSection() {
        String merged = WikiSections.replace(PAGE, 3, "== 乙 ==\n乙改了\n");
        assertEquals("导语\n\n== 甲 ==\n甲1\n\n=== 甲一 ===\n甲一内容\n\n== 乙 ==\n乙改了\n", merged);

        String lead = WikiSections.replace(PAGE, 0, "新导语");
        assertTrue(lead.startsWith("新导语\n== 甲 ==\n"), lead);

        assertNull(WikiSections.replace(PAGE, 9, "x"));
    }

    @Test
    void keepsCrlfAndSkipsBlockHeadings() {
        String crlf = "== A ==\r\nA\r\n\r\n== B ==\r\nB\r\n";
        assertEquals("== A ==\r\nA\r\n\r\n", WikiSections.extract(crlf, 1));
        assertEquals("== A ==\r\nA\r\n\r\n== B ==\r\nB2\r\n", WikiSections.replace(crlf, 2, "== B ==\r\nB2\r\n"));

        // <pre> 里的假标题不算段
        List<WikiSections.Section> pre = WikiSections.parse("== A ==\n<pre>\n== 假标题 ==\n</pre>\n\n== B ==\nB\n");
        assertEquals(2, pre.size());
        assertEquals("A", pre.get(0).heading());
        assertEquals("B", pre.get(1).heading());

        // <nowiki> 同理
        assertEquals(2, WikiSections.parse("== A ==\n<nowiki>\n== 假 ==\n</nowiki>\n== B ==\n").size());
    }

    @Test
    void skipsHeadingsInsideMarkdownBlock() {
        // <markdown> 里的 == 标题 ==（Markdown 内容 / 代码示例）不算段落标题
        String page = "== A ==\nA\n\n<markdown>\n# Markdown 标题\n\n== 假标题 ==\n</markdown>\n\n== B ==\nB\n";
        List<WikiSections.Section> sections = WikiSections.parse(page);

        assertEquals(2, sections.size());
        assertEquals("A", sections.get(0).heading());
        assertEquals("B", sections.get(1).heading());
        // 第 1 段把整个 <markdown> 块包含在里面
        assertTrue(WikiSections.extract(page, 1).contains("<markdown>"), WikiSections.extract(page, 1));
        assertEquals("== B ==\nB\n", WikiSections.extract(page, 2));

        // 同一行开关（<markdown>…</markdown>）不影响后面的标题切分
        assertEquals(2, WikiSections.parse("== A ==\n<markdown># x</markdown>\n== B ==\n").size());
    }

    @Test
    void headingAndAnchor() {
        assertEquals("甲一", WikiSections.headingOf(PAGE, 2));
        assertNull(WikiSections.headingOf(PAGE, 0));
        assertEquals("中文_标题", WikiSections.anchorOf("中文 标题"));
        assertEquals("section", WikiSections.anchorOf("[]"));
    }
}
