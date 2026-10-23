package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * 多媒体预览的前端资源（站点级注入：词条、文件页、特殊页都需要）：
 * <ul>
 *     <li>{@code /plugins/media-viewer/media-viewer.css}（播放器与图片查看器样式，原在 core 的 base.css）；</li>
 *     <li>{@code window.WIKI_CONFIG.mediaViewer = true} 与按钮文案（{@code window.WIKI_MESSAGES}）；</li>
 *     <li>{@code media-viewer.js}（播放器增强）与 {@code image-viewer.js}（图片放大查看）。</li>
 * </ul>
 */
public class MediaViewerHeadProvider implements HtmlHeadProvider {

    /** 资源内容哈希：插件更新后 URL 变化，避免浏览器继续使用旧的查看器脚本 / 样式。 */
    private static final String VERSION = assetVersion();

    private final MediaViewerSupport support;

    public MediaViewerHeadProvider(MediaViewerSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/media-viewer/media-viewer.css?v=" + VERSION + "\">",
                "<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                        + "window.WIKI_CONFIG.mediaViewer = true;"
                        + "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "mediaPlay: " + support.json("media.play") + ","
                        + "mediaPause: " + support.json("media.pause") + ","
                        + "mediaMute: " + support.json("media.mute") + ","
                        + "mediaUnmute: " + support.json("media.unmute") + ","
                        + "mediaFullscreen: " + support.json("media.fullscreen") + ","
                        + "mediaDownload: " + support.json("media.download") + ","
                        + "mediaRate: " + support.json("media.rate") + ","
                        + "mediaUnsupported: " + support.json("media.unsupported") + ","
                        + "mediaLoading: " + support.json("media.loading") + ","
                        + "imageViewerClose: " + support.json("imageViewer.close") + ","
                        + "imageViewerPrev: " + support.json("imageViewer.prev") + ","
                        + "imageViewerNext: " + support.json("imageViewer.next") + ","
                        + "imageViewerZoomIn: " + support.json("imageViewer.zoomIn") + ","
                        + "imageViewerZoomOut: " + support.json("imageViewer.zoomOut") + ","
                        + "imageViewerFit: " + support.json("imageViewer.fit") + ","
                        + "imageViewerDownload: " + support.json("imageViewer.download") + ","
                        + "imageViewerOriginal: " + support.json("imageViewer.original") + ","
                        + "imageViewerScaled: " + support.json("imageViewer.scaled") + ","
                        + "imageViewerWorking: " + support.json("imageViewer.working") + ","
                        + "imageViewerFailed: " + support.json("imageViewer.failed") + ","
                        + "imageViewerLoading: " + support.json("imageViewer.loading")
                        + "});</script>",
                "<script defer src=\"/plugins/media-viewer/media-viewer.js?v=" + VERSION + "\"></script>",
                "<script defer src=\"/plugins/media-viewer/image-viewer.js?v=" + VERSION + "\"></script>");
    }

    /** 把 CSS / 两个 JS 的内容一起算哈希；任一资源更新都会换 URL。 */
    private static String assetVersion() {
        String[] resources = {
                "/static/plugins/media-viewer/media-viewer.css",
                "/static/plugins/media-viewer/media-viewer.js",
                "/static/plugins/media-viewer/image-viewer.js"
        };
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (String resource : resources) {
                try (InputStream input = MediaViewerHeadProvider.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        return "1";
                    }
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value));
            }
            return out.substring(0, 12);
        } catch (Exception exception) {
            return "1";
        }
    }
}
