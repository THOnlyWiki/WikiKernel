package cc.thonly.wikikernel.plugin.easytimeline;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * EasyTimeline 扩展移植（对应 MediaWiki 的 {@code EasyTimeline}，原为 core 内置功能）：
 * {@code <timeline>…</timeline>} 在服务端解析成**内联 SVG**（{@link TimelineRenderer}，不依赖 Ploticus/Perl）。
 * <p>
 * 兼容性：沿用 core 的功能开关 {@code features.timeline}（通过 {@link SiteConfig} 读取）；
 * 关闭时标签按原文转义显示（与迁移前一致）；解析失败的提示用消息键 {@code timeline.invalid}
 * （文案在插件自己的 {@code i18n/messages*.properties} 里，见 {@link EasyTimelineMessages}）。
 */
public class EasyTimelinePlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("easy-timeline", "EasyTimeline", "0.1.0",
                "Server-side <timeline> renderer that emits inline SVG (ported from MediaWiki EasyTimeline)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new EasyTimelineMessages());
        context.register(ParserTag.class, new TimelineTag(siteConfig, messageSource));
        context.logger().log(System.Logger.Level.INFO,
                siteConfig.getBoolean("features.timeline", true)
                        ? "已启用 <timeline>（服务端内联 SVG）"
                        : "EasyTimeline 已关闭（features.timeline=false），标签按原文显示");
    }
}
