package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.extension.FileEmbedCall;
import cc.thonly.wikikernel.common.extension.FileEmbedRenderer;

/**
 * 音视频嵌入渲染（仿 MediaWiki TimedMediaHandler）：{@code [[文件:X.webm|thumb|300px]]} 与
 * 文件页预览直接输出原生播放器（无 JS 时仍可播放），前端脚本再增强成 MediaWiki 风格控制条。
 * <p>
 * 只处理音视频；图片与其它文件返回 {@code null}，交给 core 的默认行为（图片 / 图标占位框）。
 */
public class MediaPlayerEmbedRenderer implements FileEmbedRenderer {

    private final MediaViewerSupport support;

    public MediaPlayerEmbedRenderer(MediaViewerSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String render(FileEmbedCall call) {
        if (!support.enabled()) {
            return null;
        }
        String kind = call.mediaKind();
        if (!"audio".equals(kind) && !"video".equals(kind)) {
            return null;
        }
        return player(call.url(), call.fileName(), kind, call.width());
    }

    /**
     * 播放器容器（词条嵌入与文件页共用）。
     *
     * @param url      文件直链（{@code /files/...}）
     * @param fileName 展示文件名（下载属性用）
     * @param kind     {@code audio} 或 {@code video}
     * @param width    可选最大宽度（{@code [[文件:X.webm|300px]]} 的 300）
     */
    public static String player(String url, String fileName, String kind, Integer width) {
        String tag = "audio".equals(kind) ? "audio" : "video";
        String safeUrl = MediaViewerSupport.escapeAttribute(url);
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"wiki-media-player wiki-media-").append(tag).append("\"")
                .append(" data-kind=\"").append(tag).append("\"")
                .append(" data-src=\"").append(safeUrl).append("\"")
                .append(" data-name=\"").append(MediaViewerSupport.escapeAttribute(fileName)).append("\"");
        if (width != null && width > 0) {
            html.append(" style=\"max-width:").append(width).append("px\"");
        }
        html.append(" tabindex=\"0\">");
        // 原生元素（无 JS 回退）；JS 增强后隐藏原生 controls，改用自定义控制条
        html.append("<").append(tag)
                .append(" class=\"wiki-media-element\" src=\"").append(safeUrl).append("\"")
                .append(" preload=\"metadata\" playsinline controls></").append(tag).append(">");
        html.append("<div class=\"wiki-media-fallback\" hidden></div>");
        html.append("</div>");
        return html.toString();
    }
}
