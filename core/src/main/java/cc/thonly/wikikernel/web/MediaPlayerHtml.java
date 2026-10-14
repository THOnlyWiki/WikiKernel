package cc.thonly.wikikernel.web;

import org.springframework.web.util.HtmlUtils;

/**
 * 文件占位框（仿 MediaWiki 的非媒体文件嵌入：图标 + 文件名，链到文件页）。
 * <p>
 * 音视频**播放器**已迁到插件 {@code media-viewer}（仿 TimedMediaHandler，见
 * {@code common.extension.FileEmbedRenderer}）；这里只保留「没有插件处理时」的通用回退。
 */
public final class MediaPlayerHtml {

    private MediaPlayerHtml() {
    }

    /**
     * 非图片 / 未启用预览时的文件占位框（仿 MediaWiki 的非媒体文件嵌入：图标 + 文件名，链到文件页）。
     */
    public static String fileBox(String pageUrl, String fileName, String kind) {
        String icon = switch (kind) {
            case "audio" -> "<svg class=\"wiki-file-embed-icon\" viewBox=\"0 0 20 20\" width=\"16\" height=\"16\""
                    + " aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M7 3v9.3A2.7 2.7 0 1 0 9 14.7V5h6V3z\"/></svg>";
            case "video" -> "<svg class=\"wiki-file-embed-icon\" viewBox=\"0 0 20 20\" width=\"16\" height=\"16\""
                    + " aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M2 4h11v12H2zM14 7.5 18 5v10l-4-2.5z\"/></svg>";
            default -> "<svg class=\"wiki-file-embed-icon\" viewBox=\"0 0 20 20\" width=\"16\" height=\"16\""
                    + " aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M5 2h7l4 4v12H5zM12 2v4h4\""
                    + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\"/></svg>";
        };
        return "<span class=\"wiki-file-embed\"><a href=\"" + escapeAttribute(pageUrl) + "\">"
                + icon + escapeText(fileName) + "</a></span>";
    }

    private static String escapeAttribute(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
