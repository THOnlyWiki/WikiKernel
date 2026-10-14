package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * MediaWiki 导出 XML（export-0.11 兼容）：可被 MediaWiki 的 {@code Special:Import} 导入。
 * <p>
 * 只输出系统间的公共字段：标题 / 命名空间 / 版本 / 时间（UTC）/ 贡献者 / 摘要 / 正文（sha1、bytes）；
 * 已隐藏的版本不输出（MediaWiki 的 deleted 语义无法安全往返）；保护、隐藏、机器人标记等本地字段
 * 请使用 WikiKernel XML / JSON 格式导出。
 */
public final class MediaWikiXmlExporter {

    /** MediaWiki 导出 XML 的命名空间。 */
    public static final String NAMESPACE = "http://www.mediawiki.org/xml/export-0.11/";

    private MediaWikiXmlExporter() {
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
        // JDK StAX 在 repair 关闭时不能给 writeStartElement 传命名空间；先建根元素再声明默认命名空间，
        // 子元素不带前缀地写在默认命名空间里，输出依旧是标准的 MediaWiki 导出 XML。
        writer.writeStartElement("mediawiki");
        writer.writeDefaultNamespace(NAMESPACE);
        writer.writeAttribute("version", "0.11");

        writeSiteInfo(writer, data);

        for (ExportPageData pageData : data.pages()) {
            writePage(writer, data, pageData);
        }

        writer.writeEndElement(); // mediawiki
        writer.writeEndDocument();
        writer.flush();
    }

    private static void writeSiteInfo(XMLStreamWriter writer, ExportData data) throws XMLStreamException {
        writer.writeStartElement("siteinfo");
        element(writer, "sitename", data.siteName());
        element(writer, "dbname", "wikikernel");
        element(writer, "base", data.baseUrl());
        element(writer, "generator", data.generator());
        element(writer, "case", "first-letter");
        writer.writeStartElement("namespaces");
        for (TransferNamespaces.Entry entry : TransferNamespaces.siteinfo()) {
            writer.writeStartElement("namespace");
            writer.writeAttribute("key", String.valueOf(entry.key()));
            writer.writeAttribute("case", "first-letter");
            if (!entry.name().isEmpty()) {
                writer.writeCharacters(entry.name());
            }
            writer.writeEndElement();
        }
        writer.writeEndElement(); // namespaces
        writer.writeEndElement(); // siteinfo
    }

    private static void writePage(XMLStreamWriter writer, ExportData data, ExportPageData pageData)
            throws XMLStreamException {
        WikiPage page = pageData.page();
        List<WikiRevision> revisions = pageData.revisions();

        writer.writeStartElement("page");
        element(writer, "title", page.getTitle());
        element(writer, "ns", String.valueOf(TransferNamespaces.mediaWikiId(page.getTitle())));
        element(writer, "id", String.valueOf(page.getId()));
        if (Boolean.TRUE.equals(page.getProtectedPage())) {
            element(writer, "restrictions", "edit=sysop");
        }

        Long parentId = null;
        for (WikiRevision revision : revisions) {
            if (Boolean.TRUE.equals(revision.getDeleted())) {
                continue; // 隐藏版本不输出
            }
            writeRevision(writer, data, revision, parentId);
            parentId = revision.getId();
        }
        writer.writeEndElement(); // page
    }

    private static void writeRevision(XMLStreamWriter writer, ExportData data, WikiRevision revision, Long parentId)
            throws XMLStreamException {
        String content = revision.getContent() == null ? "" : revision.getContent();

        writer.writeStartElement("revision");
        element(writer, "id", String.valueOf(revision.getId()));
        if (parentId != null) {
            element(writer, "parentid", String.valueOf(parentId));
        }
        element(writer, "timestamp", TransferTimes.toUtcIso(revision.getCreateAt()));

        Long userId = revision.getUserId();
        String username = userId == null ? null : data.userNames().get(userId);
        if (username == null || username.isBlank()) {
            writer.writeEmptyElement("contributor");
            writer.writeAttribute("deleted", "deleted");
        } else {
            writer.writeStartElement("contributor");
            element(writer, "username", username);
            element(writer, "id", String.valueOf(userId));
            writer.writeEndElement();
        }

        element(writer, "comment", revision.getComment() == null ? "" : revision.getComment());
        element(writer, "model", "wikitext");
        element(writer, "format", "text/x-wiki");

        writer.writeStartElement("text");
        writer.writeAttribute("bytes", String.valueOf(WikiSha1.bytes(content)));
        writer.writeAttribute("sha1", WikiSha1.base36(content));
        writer.writeCharacters(TransferXml.clean(content));
        writer.writeEndElement(); // text

        writer.writeEndElement(); // revision
    }

    private static void element(XMLStreamWriter writer, String name, String text) throws XMLStreamException {
        writer.writeStartElement(name);
        writer.writeCharacters(TransferXml.clean(text));
        writer.writeEndElement();
    }
}
