package cc.thonly.wikikernel.plugin.templatedata.service;

import java.util.Map;

/**
 * 极简 HTML 构造器（仿 MediaWiki 的 {@code Html} 助手）。
 * <p>
 * 所有用户数据都必须经过 {@link #escape(String)} 转义；属性值同样转义（引号、换行、制表符），
 * 与 MediaWiki 的 {@code Html::expandAttributes()} 行为一致。
 * <p>
 * 只实现渲染文档表格需要的部分：{@code Html::element} / {@code Html::rawElement} / {@code Html::errorBox}。
 */
final class Html {

    private Html() {
    }

    /** 转义文本（对应 {@code htmlspecialchars(..., ENT_QUOTES)}）。 */
    static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 转义属性值：在 {@link #escape(String)} 之外把换行 / 制表符写成字符引用，避免被 HTML 解析器吃掉。 */
    static String escapeAttribute(String text) {
        return escape(text).replace("\n", "&#10;").replace("\t", "&#9;").replace("\r", "&#13;");
    }

    /** 元素，内容按文本转义。 */
    static String element(String tag, Map<String, Object> attributes, String content) {
        return '<' + tag + attributes(attributes) + '>' + escape(content) + "</" + tag + '>';
    }

    /** 元素，内容已是可信 HTML（不转义）。 */
    static String rawElement(String tag, Map<String, Object> attributes, String rawContent) {
        return '<' + tag + attributes(attributes) + '>' + (rawContent == null ? "" : rawContent) + "</" + tag + '>';
    }

    /** 渲染属性：值为 {@code null}、{@code false} 或空串时跳过该属性。 */
    static String attributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Object value = entry.getValue();
            if (value == null || Boolean.FALSE.equals(value)) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.isEmpty()) {
                continue;
            }
            out.append(' ').append(entry.getKey()).append("=\"").append(escapeAttribute(text)).append('"');
        }
        return out.toString();
    }

    /** 属性表：按顺序给出键值（值为 null 的键会被跳过）。 */
    static Map<String, Object> attrs(Object... keysAndValues) {
        Map<String, Object> attributes = Values.newMap();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            attributes.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return attributes;
    }

    /** 错误框：{@code <div class="errorbox"><p>消息</p></div>}。 */
    static String errorBox(String message) {
        return rawElement("div", attrs("class", "errorbox"), element("p", null, message));
    }
}
