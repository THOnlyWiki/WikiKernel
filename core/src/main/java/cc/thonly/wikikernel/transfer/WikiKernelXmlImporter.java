package cc.thonly.wikikernel.transfer;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * WikiKernel XML 解析器（{@link WikiKernelXmlExporter} 的逆操作）：
 * 读取 {@code <page>} 的保护 / 隐藏 / 删除状态与 {@code <revision>} 的机器人标记、标签、隐藏标记。
 */
public final class WikiKernelXmlImporter {

    private WikiKernelXmlImporter() {
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
        String title = attr(reader, "title");
        boolean protectedPage = bool(attr(reader, "protected"));
        boolean hided = bool(attr(reader, "hided"));
        boolean deleted = bool(attr(reader, "deleted"));

        List<ImportedRevision> revisions = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("revision".equals(reader.getLocalName())) {
                    revisions.add(readRevision(reader));
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "page".equals(reader.getLocalName())) {
                break;
            }
        }
        return new ImportedPage(title, protectedPage, hided, deleted, revisions);
    }

    private static ImportedRevision readRevision(XMLStreamReader reader) throws XMLStreamException {
        LocalDateTime time = parseTime(attr(reader, "timestamp"));
        String username = attr(reader, "user");
        Long userId = parseLong(attr(reader, "userid"));
        boolean bot = bool(attr(reader, "bot"));
        boolean deleted = bool(attr(reader, "deleted"));
        String tags = attr(reader, "tags");
        String comment = "";
        String content = "";

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "comment" -> comment = reader.getElementText();
                    case "text" -> content = reader.getElementText();
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "revision".equals(reader.getLocalName())) {
                break;
            }
        }
        return new ImportedRevision(time, username == null || username.isBlank() ? null : username, userId,
                comment, content, bot, tags == null ? "" : tags, deleted);
    }

    private static String attr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static boolean bool(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDateTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? null : TransferTimes.fromOffsetIso(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

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
}
