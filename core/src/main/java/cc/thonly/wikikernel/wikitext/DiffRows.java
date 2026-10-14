package cc.thonly.wikikernel.wikitext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行级差异 → 两栏 diff 行模型（差异对比页、编辑冲突提示、审核队列共用同一套模板 class）。
 * <p>
 * 原本是 {@code WikiPageController} 的私有方法，审核队列也要展示待审内容与当前版本的差异，
 * 因此抽成公共工具（行为不变）。
 */
public final class DiffRows {

    private DiffRows() {
    }

    /** 生成 diff 行：相同行、删除行、新增行，以及成对的变化行（中间段用于内联加粗）。 */
    public static List<Map<String, Object>> build(String oldContent, String newContent) {
        List<LineDiff.Line> lines = LineDiff.diff(oldContent, newContent);
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            LineDiff.Line line = lines.get(index);
            if (line.same()) {
                rows.add(sameRow(line));
                index++;
                continue;
            }
            // 连续的变化块：删除与新增按位置配对做内联高亮（仿 .diffchange）
            List<LineDiff.Line> block = new ArrayList<>();
            while (index < lines.size() && !lines.get(index).same()) {
                block.add(lines.get(index++));
            }
            List<LineDiff.Line> removes = block.stream().filter(LineDiff.Line::removed).toList();
            List<LineDiff.Line> adds = block.stream().filter(LineDiff.Line::added).toList();
            int pairs = Math.min(removes.size(), adds.size());
            for (int i = 0; i < pairs; i++) {
                rows.add(replaceRow(removes.get(i), adds.get(i)));
            }
            for (int i = pairs; i < removes.size(); i++) {
                rows.add(singleRow(removes.get(i)));
            }
            for (int i = pairs; i < adds.size(); i++) {
                rows.add(singleRow(adds.get(i)));
            }
        }
        return rows;
    }

    private static Map<String, Object> sameRow(LineDiff.Line line) {
        return row(line.oldLine(), true, "", line.text(), "", "",
                line.newLine(), true, "", line.text(), "", "");
    }

    private static Map<String, Object> replaceRow(LineDiff.Line remove, LineDiff.Line add) {
        LineDiff.Highlight[] highlights = LineDiff.highlightPair(remove.text(), add.text());
        return row(remove.oldLine(), true, "diff-deletedline",
                highlights[0].before(), highlights[0].change(), highlights[0].after(),
                add.newLine(), true, "diff-addedline",
                highlights[1].before(), highlights[1].change(), highlights[1].after());
    }

    private static Map<String, Object> singleRow(LineDiff.Line line) {
        // 整行新增 / 删除：整段文本包进 .diffchange（与 MediaWiki 的 <ins>/<del class="diffchange"> 一致）
        if (line.removed()) {
            return row(line.oldLine(), true, "diff-deletedline", "", line.text(), "",
                    0, false, "", "", "", "");
        }
        return row(0, false, "", "", "", "",
                line.newLine(), true, "diff-addedline", "", line.text(), "");
    }

    /** 组装一行两栏 diff（行号 / 标记 / 单元格 class / 文本三段——中间段用于内联加粗）。 */
    private static Map<String, Object> row(int oldNo, boolean oldPresent, String oldExtraClass,
                                           String oldBefore, String oldChange, String oldAfter,
                                           int newNo, boolean newPresent, String newExtraClass,
                                           String newBefore, String newChange, String newAfter) {
        Map<String, Object> row = new LinkedHashMap<>();
        boolean oldBlank = oldBefore.isEmpty() && oldChange.isEmpty() && oldAfter.isEmpty();
        row.put("oldNo", oldPresent ? oldNo : "");
        row.put("oldMarker", oldPresent && !oldExtraClass.isEmpty() ? "-" : "");
        row.put("oldClass", cellClass(oldPresent, oldExtraClass));
        row.put("oldBefore", oldPresent && !oldBlank ? oldBefore : "\u00a0");
        row.put("oldChange", oldPresent ? oldChange : "");
        row.put("oldAfter", oldPresent && !oldBlank ? oldAfter : "");
        boolean newBlank = newBefore.isEmpty() && newChange.isEmpty() && newAfter.isEmpty();
        row.put("newNo", newPresent ? newNo : "");
        row.put("newMarker", newPresent && !newExtraClass.isEmpty() ? "+" : "");
        row.put("newClass", cellClass(newPresent, newExtraClass));
        row.put("newBefore", newPresent && !newBlank ? newBefore : "\u00a0");
        row.put("newChange", newPresent ? newChange : "");
        row.put("newAfter", newPresent && !newBlank ? newAfter : "");
        return row;
    }

    private static String cellClass(boolean present, String extraClass) {
        if (!present) {
            return "diff-line diff-empty";
        }
        return extraClass.isEmpty() ? "diff-line" : "diff-line " + extraClass;
    }
}
