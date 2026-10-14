package cc.thonly.wikikernel.transfer;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaWiki 导出 XML（export-0.11）解析器：
 * 读取 {@code <page>} 下的 {@code title / ns / revision}，兼容旧式直接 {@code <text>}
 * 与多内容修订（MCR）的 {@code <slots><slot role="main"><text/>} 两种写法。
 */
public final class MediaWikiXmlImporter {

    private MediaWikiXmlImporter() {
    }

    public static List<ImportedPage> parse(byte[] bytes) throws XMLStreamException {
        XMLStreamReader reader = TransferXml.inputFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
        List<ImportedPage> pages = new ArrayList<>();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "page".equals(reader.getLocalName())) {
                    ImportedPage page = readPage(reader);
                    if (page != null && !page.title().isBlank()) {
                        pages.add(page);
                    }
                }
            }
        } finally {
            reader.close();
        }
        return pages;
    }

    private static ImportedPage readPage(XMLStreamReader reader) throws XMLStreamException {
        String title = "";
        List<ImportedRevision> revisions = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "title" -> title = readText(reader);
                    case "revision" -> revisions.add(readRevision(reader));
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "page".equals(reader.getLocalName())) {
                break;
            }
        }
        return new ImportedPage(title, false, false, false, revisions);
    }

    private static ImportedRevision readRevision(XMLStreamReader reader) throws XMLStreamException {
        RevisionData data = new RevisionData();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "timestamp" -> data.time = TransferTimes.fromUtcIso(readText(reader));
                    case "contributor" -> data.username = readContributor(reader);
                    case "comment" -> data.comment = readText(reader);
                    case "text" -> data.content = readText(reader);
                    case "slots" -> readSlots(reader, data);
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "revision".equals(reader.getLocalName())) {
                break;
            }
        }
        return new ImportedRevision(data.time, data.username, null,
                data.comment == null ? "" : data.comment,
                data.content == null ? "" : data.content,
                false, "", false);
    }

    /** {@code <contributor>}：取 username；匿名（ip）返回 null；{@code deleted} 也返回 null。 */
    private static String readContributor(XMLStreamReader reader) throws XMLStreamException {
        String username = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("username".equals(reader.getLocalName())) {
                    username = readText(reader);
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "contributor".equals(reader.getLocalName())) {
                break;
            }
        }
        return username;
    }

    /** 多内容修订：只取主插槽（role 缺省或 main）的 text。 */
    private static void readSlots(XMLStreamReader reader, RevisionData data) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("slot".equals(reader.getLocalName())) {
                    readSlot(reader, data);
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "slots".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private static void readSlot(XMLStreamReader reader, RevisionData data) throws XMLStreamException {
        String role = reader.getAttributeValue(null, "role");
        boolean main = role == null || "main".equals(role);
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (main && "text".equals(reader.getLocalName())) {
                    data.content = readText(reader);
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "slot".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private static String readText(XMLStreamReader reader) throws XMLStreamException {
        return reader.getElementText();
    }

    /** 跳过当前元素（及其子树），结束后游标停在该元素的 END_ELEMENT。 */
    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    /** readRevision 的可变收集器。 */
    private static final class RevisionData {
        private LocalDateTime time;
        private String username;
        private String comment = "";
        private String content = "";
    }
}
