package cc.thonly.wikikernel.plugin.plainskin;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.Skin;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Plain 皮肤插件：向 core 注册一个 {@code plain} 皮肤，皮肤资源全部随插件 jar 分发：
 * <ul>
 *     <li>{@code templates/skins/plain/layout.html}：皮肤布局（由 PluginTemplateResolver 从插件 jar 解析）；</li>
 *     <li>{@code static/skins/plain/plain.css}：皮肤样式（由 core 的 {@code /skins/plain/...} 端点读取）；</li>
 *     <li>{@code context.register(Skin.class, ...)}：注册皮肤。</li>
 * </ul>
 * 本地化文案在插件自己的消息包里（{@link PlainSkinMessages}，
 * 见 {@code i18n/messages*.properties}）：插件名 / 描述、皮肤名 / 说明与安装向导文案。
 */
public class PlainSkinPlugin implements WikiPlugin {

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("plain-skin", "PlainSkin", "1.0.0",
                "Plain skin plugin: provides the minimal plain skin (layout and CSS ship inside the plugin jar)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new PlainSkinMessages());
        context.register(Skin.class, new PlainSkin());
        context.register(SetupStep.class, new PlainSkinSetup(messageSource));
        context.logger().log(System.Logger.Level.INFO, "已注册插件皮肤 plain");
    }
}
