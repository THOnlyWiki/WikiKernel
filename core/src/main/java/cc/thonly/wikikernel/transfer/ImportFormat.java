package cc.thonly.wikikernel.transfer;

import java.nio.charset.StandardCharsets;

/**
 * 导入文件格式（自动识别）：
 * <ul>
 *     <li>{@link #MEDIAWIKI_XML}：MediaWiki 的 Special:Export 产出（根元素 {@code mediawiki}）；</li>
 *     <li>{@link #WIKIKERNEL_XML}：本 wiki 的 XML（根元素 {@code wikikernel}）；</li>
 *     <li>{@link #WIKIKERNEL_JSON}：本 wiki 的 JSON（以 {@code {} 开头）；</li>
 *     <li>{@link #UNKNOWN}：无法识别。</li>
 * </ul>
 */
public enum ImportFormat {

    MEDIAWIKI_XML,
    WIKIKERNEL_XML,
    WIKIKERNEL_JSON,
    UNKNOWN;

    /** 探测文件格式：JSON 看首个非空白字符；XML 需要解析出根元素名。 */
    public static ImportFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return UNKNOWN;
        }
        int start = 0;
        // 跳过 UTF-8 BOM 与空白
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < bytes.length && isWhitespace(bytes[start])) {
            start++;
        }
        if (start >= bytes.length) {
            return UNKNOWN;
        }
        byte first = bytes[start];
        if (first == '{' || first == '[') {
            return WIKIKERNEL_JSON;
        }
        if (first != '<') {
            return UNKNOWN;
        }
        String root = TransferXml.rootElementName(bytes);
        if ("mediawiki".equalsIgnoreCase(root)) {
            return MEDIAWIKI_XML;
        }
        if ("wikikernel".equalsIgnoreCase(root)) {
            return WIKIKERNEL_XML;
        }
        return UNKNOWN;
    }

    private static boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /** 文件头是否像 JSON（供错误提示区分「解析失败」与「格式不支持」）。 */
    static boolean looksLikeJson(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8);
        return head.stripLeading().startsWith("{") || head.stripLeading().startsWith("[");
    }
}
