package cc.thonly.wikikernel.actionapi;

import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * 简化的 MediaWiki XML 输出（{@code format=xml}）：
 * 映射 → 同名元素；列表 → {@code <item>} 序列；标量 → 文本；非法元素名退回 {@code <entry name="…">}。
 * 与 MediaWiki 的 XML 形状不完全一致（MediaWiki 用单数元素名），但结构可直接解析。
 */
public final class ActionApiXml {

    private ActionApiXml() {
    }

    public static String toXml(Object root) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<api>");
        appendValue(xml, root);
        return xml.append("</api>").toString();
    }

    private static void appendValue(StringBuilder xml, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                appendEntry(xml, String.valueOf(entry.getKey()), entry.getValue());
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                appendEntry(xml, "item", item);
            }
            return;
        }
        xml.append(escape(String.valueOf(value)));
    }

    private static void appendEntry(StringBuilder xml, String name, Object value) {
        boolean valid = name.matches("[A-Za-z_][A-Za-z0-9_.-]*") && !name.toLowerCase().startsWith("xml");
        if (valid) {
            if (value == null) {
                xml.append('<').append(name).append("/>");
                return;
            }
            xml.append('<').append(name).append('>');
            appendValue(xml, value);
            xml.append("</").append(name).append('>');
            return;
        }
        // 非法元素名（如数字 ID）：<entry name="…">值</entry>
        xml.append("<entry name=\"").append(escape(name)).append("\">");
        appendValue(xml, value);
        xml.append("</entry>");
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
