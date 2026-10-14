package cc.thonly.wikikernel.plugin.categorytree.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTML 拼装工具：分类树的全部输出都在这里拼字符串，所有属性 / 文本都必须经过转义
 * （插件不能复用 core 内部的 {@code HtmlSanitizer}，所以这里自带一份等价实现）。
 */
public final class CategoryTreeHtml {

    private CategoryTreeHtml() {
    }

    /** 文本转义（&amp; &lt; &gt;）。 */
    public static String escapeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 属性值转义（在文本转义基础上再转义双引号）。 */
    static String escapeAttribute(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return escapeText(value).replace("\"", "&quot;");
    }

    /**
     * 生成一个 HTML 元素。
     *
     * @param name       标签名
     * @param attributes 属性（{@code null} 或空串的值会被忽略；值为空串时输出裸属性）
     * @param inner      已可信的内部 HTML（调用方自行转义）
     */
    static String element(String name, Map<String, String> attributes, String inner) {
        StringBuilder out = new StringBuilder("<").append(name);
        appendAttributes(out, attributes);
        out.append('>').append(inner == null ? "" : inner).append("</").append(name).append('>');
        return out.toString();
    }

    /** 属性片段（{@code key="value"}，值为空串时输出裸属性名）。 */
    static String attributes(Map<String, String> attributes) {
        StringBuilder out = new StringBuilder();
        appendAttributes(out, attributes);
        return out.toString();
    }

    private static void appendAttributes(StringBuilder out, Map<String, String> attributes) {
        if (attributes == null) {
            return;
        }
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            out.append(' ').append(key);
            if (!value.isEmpty()) {
                out.append("=\"").append(escapeAttribute(value)).append('"');
            }
        }
    }

    /** 便捷的 {@link LinkedHashMap} 构造（属性顺序稳定，便于阅读与缓存）。 */
    static Map<String, String> newAttributes() {
        return new LinkedHashMap<>();
    }
}
