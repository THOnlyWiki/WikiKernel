package cc.thonly.wikikernel.plugin.flashplayer;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Flash 播放器扩展移植（对应 core 内置的 {@code <flash-player>}，行为对齐 Ruffle 方案）：
 * <ul>
 *     <li>{@code <flash-player file="[[文件:X.swf]] 或 URL" … />} 生成播放器占位（{@link FlashPlayerTag}）；</li>
 *     <li>{@code /plugins/flash-player/flash-player.js} 懒加载 core 内置的 Ruffle（{@code /vendor/ruffle/…}），
 *     由 {@link FlashPlayerHeadProvider} 在功能开启时注入。</li>
 * </ul>
 * 兼容性：沿用 core 的功能开关 {@code features.flash-player}（通过 {@link SiteConfig} 读取）；
 * 关闭时退化为文件 / 链接（与迁移前一致）；文件解析走 {@link FileStore} 桥接。
 * 本地化文案在插件自己的消息包里（{@link FlashPlayerMessages}，见 {@code i18n/messages*.properties}）。
 */
public class FlashPlayerPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private FileStore fileStore;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("flash-player", "Flash Player (Ruffle)", "0.1.0",
                "Plays Flash content in the page with Ruffle (the <flash-player> tag, extracted from core)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new FlashPlayerMessages());
        context.register(ParserTag.class, new FlashPlayerTag(siteConfig, fileStore, messageSource));
        context.register(HtmlHeadProvider.class, new FlashPlayerHeadProvider(siteConfig, messageSource));
        context.logger().log(System.Logger.Level.INFO,
                siteConfig.getBoolean("features.flash-player", true)
                        ? "已启用 <flash-player>（Ruffle 前端播放）"
                        : "Flash 播放器已关闭（features.flash-player=false），标签退化为文件链接");
    }
}
