package cc.thonly.wikikernel.wikitext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Paragraphs include their line breaks so that choosing either revision preserves its text. */
public final class ParagraphDiff {
    private static final Pattern PARAGRAPH = Pattern.compile("(?s).+?(?:\\n[\\t ]*\\n+|\\z)");

    private ParagraphDiff() {}

    public record Block(boolean same, String current, String submitted,
                        LineDiff.Highlight currentHighlight, LineDiff.Highlight submittedHighlight) {}

    public static List<Block> compare(String current, String submitted) {
        List<Block> result = new ArrayList<>();
        StringBuilder live = new StringBuilder();
        StringBuilder mine = new StringBuilder();
        boolean same = false;
        for (var part : LineDiff.diffLines(paragraphs(current), paragraphs(submitted))) {
            if (same != part.same()) {
                append(result, same, live, mine);
                same = part.same();
            }
            if (!part.added()) {
                live.append(part.text());
            }
            if (!part.removed()) {
                mine.append(part.text());
            }
        }
        append(result, same, live, mine);
        return List.copyOf(result);
    }

    private static void append(List<Block> blocks, boolean same, StringBuilder live, StringBuilder mine) {
        if (live.isEmpty() && mine.isEmpty()) {
            return;
        }
        String current = live.toString();
        String submitted = mine.toString();
        LineDiff.Highlight[] pair = LineDiff.highlightPair(current, submitted);
        blocks.add(new Block(same, current, submitted, pair[0], pair[1]));
        live.setLength(0);
        mine.setLength(0);
    }

    private static List<String> paragraphs(String text) {
        return PARAGRAPH.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).results()
                .map(java.util.regex.MatchResult::group).toList();
    }
}
