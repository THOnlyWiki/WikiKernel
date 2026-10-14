package cc.thonly.wikikernel.transfer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * 导出 / 导入共用的 XML 工具：
 * <ul>
 *     <li>安全解析工厂（关闭 DTD 与外部实体，防 XXE）；</li>
 *     <li>XML 1.0 非法控制字符清理（页面正文可能含任意字符）；</li>
 *     <li>定位根元素名（格式识别用）。</li>
 * </ul>
 */
public final class TransferXml {

    private TransferXml() {
    }

    /** 关闭 DTD / 外部实体的解析工厂（导入不可信文件必须走这里）。 */
    public static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        setProperty(factory, XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /** 输出工厂（导出用）。 */
    public static XMLOutputFactory outputFactory() {
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try {
            factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, false);
        } catch (IllegalArgumentException ignored) {
            // 某些实现不支持该属性；忽略即可
        }
        return factory;
    }

    /** 去掉 XML 1.0 不允许的字符（否则写出时会抛异常）。 */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = c == '\t' || c == '\n' || c == '\r'
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1)));
            if (allowed) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** 读根元素名（找不到返回空串）。 */
    public static String rootElementName(byte[] bytes) {
        try {
            XMLStreamReader reader = inputFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        return reader.getLocalName();
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ignored) {
            // 交给上层报「解析失败」
        }
        return "";
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // 实现不支持时忽略
        }
    }
}
