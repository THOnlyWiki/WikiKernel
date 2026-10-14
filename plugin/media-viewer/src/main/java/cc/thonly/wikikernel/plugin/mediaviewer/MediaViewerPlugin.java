package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.extension.FileEmbedRenderer;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * 多媒体预览移植（对应 MediaWiki 的 {@code TimedMediaHandler} 播放器 + {@code MediaViewer} 图片放大）：
 * <ul>
 *     <li>音视频嵌入（{@code [[文件:X.webm|thumb|300px]]}）与文件页预览用原生
 *     {@code <video controls>} / {@code <audio controls>}（无 JS 时仍可播放），
 *     {@link MediaViewerHeadProvider} 注入的脚本再把它增强成 MediaWiki 风格控制条；</li>
 *     <li>图片链接点击放大查看（仿 MediaViewer）：缩放 / 下载 / 原图；</li>
 *     <li>播放器与查看器的样式（{@code media-viewer.css}）也从 core 的 base.css 迁到这里。</li>
 * </ul>
 * 开关沿用 core 的功能开关 {@code features.media-viewer}（默认关闭）；关闭时音视频只显示文件占位框、
 * 图片点击不放大（与迁移前的行为一致）。
 */
public class MediaViewerPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.media-viewer";

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("media-viewer", "TimedMediaHandler / MediaViewer", "1.0.0",
                "Audio / video player and image lightbox, ported from MediaWiki TimedMediaHandler and MediaViewer");
    }

    @Override
    public void onStart(PluginContext context) {
        MediaViewerSupport support = new MediaViewerSupport(siteConfig, messageSource);
        context.register(MessageBundle.class, new MediaViewerMessages());
        context.register(FileEmbedRenderer.class, new MediaPlayerEmbedRenderer(support));
        context.register(HtmlHeadProvider.class, new MediaViewerHeadProvider(support));
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用多媒体预览：音视频播放器 + 图片放大查看（/plugins/media-viewer/）"
                : "多媒体预览已关闭（features.media-viewer=false）：音视频显示文件占位框");
    }
}
