package cc.thonly.wikikernel.plugin.inputbox;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * InputBox 扩展移植（对应 MediaWiki 的 {@code InputBox}，原为 core 内置功能）：
 * <ul>
 *     <li>{@code <inputbox>key=value…</inputbox>} → 创建页面 / 搜索表单（{@link InputBoxTag}）；</li>
 *     <li>{@code /plugins/input-box/inputbox.js}（prefix / preload / editintro 的前端组装，{@link InputBoxHeadProvider} 注入）。</li>
 * </ul>
 * 兼容性：沿用 core 的功能开关 {@code features.input-box}（通过 {@link SiteConfig} 读取）——
 * 关闭时按原文转义显示（与迁移前一致）；文本沿用 core 消息键 {@code common.search} / {@code special.createPage}，
 * 插件自己的文案（插件名 / 描述）在 {@link InputBoxMessages} 的 {@code i18n/messages*.properties} 里。
 */
public class InputBoxPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("input-box", "InputBox", "0.1.0",
                "The <inputbox> tag: create-page and search forms (ported from MediaWiki InputBox)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new InputBoxMessages());
        context.register(ParserTag.class, new InputBoxTag(siteConfig, messageSource));
        context.register(HtmlHeadProvider.class, new InputBoxHeadProvider(siteConfig));
        context.logger().log(System.Logger.Level.INFO,
                siteConfig.getBoolean("features.input-box", true)
                        ? "已启用 <inputbox>（创建 / 搜索表单）"
                        : "InputBox 已关闭（features.input-box=false），标签按原文显示");
    }
}
