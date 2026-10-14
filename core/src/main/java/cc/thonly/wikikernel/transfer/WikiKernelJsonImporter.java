package cc.thonly.wikikernel.transfer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * WikiKernel JSON 解析器（{@link WikiKernelJsonExporter} 的逆操作）。
 */
public final class WikiKernelJsonImporter {

    private WikiKernelJsonImporter() {
    }

    public static List<ImportedPage> parse(byte[] bytes, ObjectMapper mapper) {
        JsonNode root = mapper.readTree(bytes);
        List<ImportedPage> pages = new ArrayList<>();
        JsonNode pagesNode = root == null ? null : root.get("pages");
        if (pagesNode == null || !pagesNode.isArray()) {
            return pages;
        }
        for (JsonNode pageNode : pagesNode) {
            String title = text(pageNode, "title");
            if (title.isBlank()) {
                continue;
            }
            List<ImportedRevision> revisions = new ArrayList<>();
            JsonNode revisionsNode = pageNode.get("revisions");
            if (revisionsNode != null && revisionsNode.isArray()) {
                for (JsonNode revisionNode : revisionsNode) {
                    revisions.add(new ImportedRevision(
                            parseTime(text(revisionNode, "timestamp")),
                            blankToNull(text(revisionNode, "user")),
                            longValue(revisionNode, "userId"),
                            text(revisionNode, "comment"),
                            text(revisionNode, "content"),
                            bool(revisionNode, "bot"),
                            text(revisionNode, "tags"),
                            bool(revisionNode, "deleted")));
                }
            }
            pages.add(new ImportedPage(title,
                    bool(pageNode, "protected"),
                    bool(pageNode, "hided"),
                    bool(pageNode, "deleted"),
                    revisions));
        }
        return pages;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString("");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.asBoolean(false);
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static LocalDateTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? null : TransferTimes.fromOffsetIso(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
