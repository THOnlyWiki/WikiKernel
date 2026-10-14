package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WikiKernel JSON：与 {@link WikiKernelXmlExporter} 字段一致的 JSON 版本（备份 / 恢复 / 程序化处理）。
 * 时间用带时区偏移的 ISO 字符串；正文直接以字符串保存（缩进美化，便于人工查看）。
 */
public final class WikiKernelJsonExporter {

    private WikiKernelJsonExporter() {
    }

    /** 导出为 JSON 字节（UTF-8，带缩进）。 */
    public static byte[] export(ExportData data, ObjectMapper mapper) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toNode(data, mapper));
    }

    /** 导出数据 → Jackson 树（测试与其它序列化器复用）。 */
    public static ObjectNode toNode(ExportData data, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", "wikikernel");
        root.put("version", 1);
        root.put("generator", data.generator());
        root.put("exportedAt", TransferTimes.toOffsetIso(data.exportedAt()));
        ObjectNode site = root.putObject("site");
        site.put("name", data.siteName());
        site.put("base", data.baseUrl());
        root.put("truncated", data.truncated());

        ArrayNode pages = root.putArray("pages");
        for (ExportPageData pageData : data.pages()) {
            WikiPage page = pageData.page();
            ObjectNode pageNode = pages.addObject();
            pageNode.put("id", page.getId());
            pageNode.put("title", page.getTitle());
            pageNode.put("ns", TransferNamespaces.mediaWikiId(page.getTitle()));
            pageNode.put("protected", Boolean.TRUE.equals(page.getProtectedPage()));
            pageNode.put("hided", Boolean.TRUE.equals(page.getHided()));
            pageNode.put("deleted", Boolean.TRUE.equals(page.getDeleted()));
            if (page.getLatestRevisionId() != null) {
                pageNode.put("latestRevision", page.getLatestRevisionId());
            }
            ArrayNode revisions = pageNode.putArray("revisions");
            Long parentId = null;
            for (WikiRevision revision : pageData.revisions()) {
                ObjectNode revisionNode = revisions.addObject();
                revisionNode.put("id", revision.getId());
                if (parentId != null) {
                    revisionNode.put("parentId", parentId);
                }
                revisionNode.put("timestamp", TransferTimes.toOffsetIso(revision.getCreateAt()));
                Long userId = revision.getUserId();
                String username = userId == null ? null : data.userNames().get(userId);
                if (username != null && !username.isBlank()) {
                    revisionNode.put("user", username);
                }
                if (userId != null) {
                    revisionNode.put("userId", userId);
                }
                revisionNode.put("bot", Boolean.TRUE.equals(revision.getBot()));
                revisionNode.put("deleted", Boolean.TRUE.equals(revision.getDeleted()));
                if (revision.getTags() != null && !revision.getTags().isBlank()) {
                    revisionNode.put("tags", revision.getTags());
                }
                revisionNode.put("comment", revision.getComment() == null ? "" : revision.getComment());
                String content = revision.getContent() == null ? "" : revision.getContent();
                revisionNode.put("content", content);
                revisionNode.put("sha1", WikiSha1.base36(content));
                revisionNode.put("bytes", WikiSha1.bytes(content));
                parentId = revision.getId();
            }
        }
        return root;
    }
}
