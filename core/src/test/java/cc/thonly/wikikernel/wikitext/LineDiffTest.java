package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 行级 diff（Myers）：增删改识别、行号、内联高亮、退化路径。 */
class LineDiffTest {

    @Test
    void identifiesSameDeletedAndAddedLines() {
        List<LineDiff.Line> lines = LineDiff.diff("第一行\n第二行\n第三行", "第一行\n第二行改\n第三行\n新增行");

        List<String> removed = lines.stream().filter(LineDiff.Line::removed).map(LineDiff.Line::text).toList();
        List<String> added = lines.stream().filter(LineDiff.Line::added).map(LineDiff.Line::text).toList();
        assertEquals(List.of("第二行"), removed);
        assertEquals(List.of("第二行改", "新增行"), added);
        // 首尾相同行保留
        assertEquals("第一行", lines.get(0).text());
        assertTrue(lines.get(0).same());
        assertEquals("第三行", lines.get(lines.size() - 2).text());
        assertTrue(lines.get(lines.size() - 2).same());
    }

    @Test
    void lineNumbersAreOneBasedAndZeroOnMissingSide() {
        List<LineDiff.Line> lines = LineDiff.diff("a\nb", "a\nc");
        for (LineDiff.Line line : lines) {
            if (line.same()) {
                assertEquals(line.oldLine(), line.newLine());
                assertTrue(line.oldLine() > 0);
            }
            if (line.removed()) {
                assertTrue(line.oldLine() > 0);
                assertEquals(0, line.newLine());
            }
            if (line.added()) {
                assertEquals(0, line.oldLine());
                assertTrue(line.newLine() > 0);
            }
        }
    }

    @Test
    void emptyTextsBecomePureAddOrRemove() {
        List<LineDiff.Line> fromEmpty = LineDiff.diff("", "a\nb");
        assertEquals(2, fromEmpty.stream().filter(LineDiff.Line::added).count());
        assertTrue(fromEmpty.stream().allMatch(LineDiff.Line::added));

        List<LineDiff.Line> toEmpty = LineDiff.diff("a\nb", "");
        assertEquals(2, toEmpty.stream().filter(LineDiff.Line::removed).count());
        assertTrue(toEmpty.stream().allMatch(LineDiff.Line::removed));

        assertTrue(LineDiff.diff("", "").isEmpty());
    }

    @Test
    void identicalTextsAreAllSame() {
        List<LineDiff.Line> lines = LineDiff.diff("a\nb\nc", "a\nb\nc");
        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(LineDiff.Line::same));
    }

    @Test
    void trailingNewlineDoesNotCreateExtraLine() {
        assertEquals(List.of("a", "b"), LineDiff.splitLines("a\nb\n"));
        assertEquals(List.of("a", ""), LineDiff.splitLines("a\n\n"));
        assertTrue(LineDiff.splitLines("").isEmpty());
        assertTrue(LineDiff.splitLines(null).isEmpty());
    }

    @Test
    void highlightPairMarksChangedMiddle() {
        LineDiff.Highlight[] pair = LineDiff.highlightPair("hello world", "hello there");
        assertEquals("hello ", pair[0].before());
        assertEquals("world", pair[0].change());
        assertEquals("", pair[0].after());
        assertEquals("there", pair[1].change());

        LineDiff.Highlight[] extension = LineDiff.highlightPair("a", "ab");
        assertEquals("a", extension[0].before());
        assertEquals("", extension[0].change());
        assertEquals("b", extension[1].change());
    }

    @Test
    void hugeDifferencesFallBackWithoutHanging() {
        String oldText = IntStream.range(0, 1500).mapToObj(i -> "旧-" + i).reduce("", (a, b) -> a + b + "\n");
        String newText = IntStream.range(0, 1500).mapToObj(i -> "新-" + i).reduce("", (a, b) -> a + b + "\n");

        List<LineDiff.Line> lines = LineDiff.diff(oldText, newText);

        assertEquals(3000, lines.size());
        assertEquals(1500, lines.stream().filter(LineDiff.Line::removed).count());
        assertEquals(1500, lines.stream().filter(LineDiff.Line::added).count());
        assertFalse(lines.stream().anyMatch(LineDiff.Line::same));
    }
}
