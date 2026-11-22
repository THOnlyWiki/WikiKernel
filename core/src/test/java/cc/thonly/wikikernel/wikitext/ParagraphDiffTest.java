package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParagraphDiffTest {
    @Test
    void preservesSeparatorsAndSeparatesNonAdjacentChanges() {
        String current = "\n\nIntro\n\nCurrent A\n\nMiddle\n\nCurrent B\n\nEnd\n";
        String submitted = "\n\nIntro\n\nDraft A\n\nMiddle\n\nDraft B\n\nEnd\n";
        var blocks = ParagraphDiff.compare(current, submitted);
        assertEquals(List.of(true, false, true, false, true),
                blocks.stream().map(ParagraphDiff.Block::same).toList());
        assertEquals(current, blocks.stream().map(ParagraphDiff.Block::current).collect(Collectors.joining()));
        assertEquals(submitted, blocks.stream().map(ParagraphDiff.Block::submitted).collect(Collectors.joining()));
    }
}
