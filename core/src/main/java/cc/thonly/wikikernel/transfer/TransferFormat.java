package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.Locale;

/**
 * 导出格式：MediaWiki XML（可被 MediaWiki 的 Special:Import 导入）、
 * WikiKernel XML / WikiKernel JSON（含保护、隐藏、机器人标记、标签等完整本地字段）。
 */
public enum TransferFormat {

    /** MediaWiki 导出 XML（export-0.11 兼容）。 */
    MEDIAWIKI_XML("mediawiki", "xml", "text/xml; charset=UTF-8"),

    /** WikiKernel 自己的 XML（完整字段，用于备份 / 恢复）。 */
    WIKIKERNEL_XML("wikikernel-xml", "xml", "text/xml; charset=UTF-8"),

    /** WikiKernel 自己的 JSON（完整字段，用于备份 / 恢复与程序化处理）。 */
    WIKIKERNEL_JSON("wikikernel-json", "json", "application/json; charset=UTF-8");

    private final String id;
    private final String extension;
    private final String contentType;

    TransferFormat(String id, String extension, String contentType) {
        this.id = id;
        this.extension = extension;
        this.contentType = contentType;
    }

    /** URL / 表单参数里使用的稳定 id。 */
    public String id() {
        return id;
    }

    /** 导出文件扩展名（不含点）。 */
    public String extension() {
        return extension;
    }

    /** 下载响应的 Content-Type。 */
    public String contentType() {
        return contentType;
    }

    /** 参数 → 格式（未知 / 空回退 MediaWiki XML，保持与 MediaWiki 的互操作性）。 */
    public static TransferFormat fromId(String value) {
        String id = Strings.trimToEmpty(value).toLowerCase(Locale.ROOT);
        for (TransferFormat format : values()) {
            if (format.id.equals(id)) {
                return format;
            }
        }
        return MEDIAWIKI_XML;
    }
}
