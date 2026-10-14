package cc.thonly.wikikernel.plugin.notitle;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;

/**
 * NoTitle 扩展移植（对应 MediaWiki 的 {@code NoTitle} 扩展 / {@code Extension:NoTitle}）：
 * 在页面任意位置写 {@code __NOTITLE__}（中文别名 {@code __无标题__}）即可隐藏页面大标题，
 * 与 MediaWiki 版一致（MediaWiki 版通过 CSS 隐藏 {@code h1.firstHeading}；
 * WikiKernel 由行为开关写入页面属性 {@code notitle}，皮肤布局按属性隐藏）。
 * <p>
 * 本地化文案（插件名 / 描述）在插件自己的消息包里（{@link NoTitleMessages}，
 * 见 {@code i18n/messages*.properties}）；开关名与别名是页面语法，不随界面语言变化。
 */
public class NoTitlePlugin implements WikiPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("no-title", "NoTitle", "0.4.0",
                "Hides the page title with the __NOTITLE__ behavior switch (ported from MediaWiki NoTitle)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new NoTitleMessages());
        context.register(BehaviorSwitch.class, new NoTitleSwitch());
        context.logger().log(System.Logger.Level.INFO, "已注册行为开关 __NOTITLE__ / __无标题__");
    }
}
