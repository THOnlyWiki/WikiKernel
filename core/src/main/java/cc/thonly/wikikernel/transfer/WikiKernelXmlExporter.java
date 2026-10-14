package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;

/**
 * WikiKernel XML：本 wiki 的完整导出（备份 / 恢复）。
 * <p>
 * 比 MediaWiki XML 多出：页面保护 / 隐藏 / 删除状态、版本机器人标记 / 标签 / 隐藏标记、
 * 原站用户 ID、带时区偏移的时间戳（换服务器时区也能还原）。根元素 {@code <wikikernel>}。
 */
public final class WikiKernelXmlExporter {

    private WikiKernelXmlExporter() {
    }

    /** 导出为 XML 字节（UTF-8）。 */
    public static byte[] export(ExportData data) throws XMLStreamException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLStreamWriter writer = TransferXml.outputFactory().createXMLStreamWriter(out, "UTF-8");
        try {
            write(writer, data);
        } finally {
            writer.close();
        }
        return out.toByteArray();
    }

    private static void write(XMLStreamWriter writer, ExportData data) throws XMLStreamException {
        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeStartElement("wikikernel");
        writer.writeAttribute("version", "1");
        writer.writeAttribute("generator", data.generator());
        writer.writeAttribute("exported-at", TransferTimes.toOffsetIso(data.exportedAt()));
        writer.writeAttribute("site", TransferXml.clean(data.siteName()));
        writer.writeAttribute("base", TransferXml.clean(data.baseUrl()));
        if (data.truncated()) {
            writer.writeAttribute("truncated", "true");
        }

        for (ExportPageData pageData : data.pages()) {
            writePage(writer, data, pageData);
        }

        writer.writeEndElement(); // wikikernel
        writer.writeEndDocument();
        writer.flush();
    }

    private static void writePage(XMLStreamWriter writer, ExportData data, ExportPageData pageData)
            throws XMLStreamException {
        WikiPage page = pageData.page();
        writer.writeStartElement("page");
        writer.writeAttribute("id", String.valueOf(page.getId()));
        writer.writeAttribute("title", TransferXml.clean(page.getTitle()));
        writer.writeAttribute("ns", String.valueOf(TransferNamespaces.mediaWikiId(page.getTitle())));
        writer.writeAttribute("protected", String.valueOf(Boolean.TRUE.equals(page.getProtectedPage())));
        writer.writeAttribute("hided", String.valueOf(Boolean.TRUE.equals(page.getHided())));
        writer.writeAttribute("deleted", String.valueOf(Boolean.TRUE.equals(page.getDeleted())));
        if (page.getLatestRevisionId() != null) {
            writer.writeAttribute("latest", String.valueOf(page.getLatestRevisionId()));
        }

        Long parentId = null;
        for (WikiRevision revision : pageData.revisions()) {
            writeRevision(writer, data, revision, parentId);
            parentId = revision.getId();
        }
        writer.writeEndElement(); // page
    }

    private static void writeRevision(XMLStreamWriter writer, ExportData data, WikiRevision revision, Long parentId)
            throws XMLStreamException {
        String content = revision.getContent() == null ? "" : revision.getContent();
        Long userId = revision.getUserId();
        String username = userId == null ? null : data.userNames().get(userId);

        writer.writeStartElement("revision");
        writer.writeAttribute("id", String.valueOf(revision.getId()));
        if (parentId != null) {
            writer.writeAttribute("parentid", String.valueOf(parentId));
        }
        writer.writeAttribute("timestamp", TransferTimes.toOffsetIso(revision.getCreateAt()));
        if (username != null && !username.isBlank()) {
            writer.writeAttribute("user", TransferXml.clean(username));
        }
        if (userId != null) {
            writer.writeAttribute("userid", String.valueOf(userId));
        }
        writer.writeAttribute("bot", String.valueOf(Boolean.TRUE.equals(revision.getBot())));
        writer.writeAttribute("deleted", String.valueOf(Boolean.TRUE.equals(revision.getDeleted())));
        if (revision.getTags() != null && !revision.getTags().isBlank()) {
            writer.writeAttribute("tags", TransferXml.clean(revision.getTags()));
        }

        writer.writeStartElement("comment");
        writer.writeCharacters(TransferXml.clean(revision.getComment() == null ? "" : revision.getComment()));
        writer.writeEndElement();

        writer.writeStartElement("text");
        writer.writeAttribute("bytes", String.valueOf(WikiSha1.bytes(content)));
        writer.writeAttribute("sha1", WikiSha1.base36(content));
        writer.writeCharacters(TransferXml.clean(content));
        writer.writeEndElement();

        writer.writeEndElement(); // revision
    }
}
