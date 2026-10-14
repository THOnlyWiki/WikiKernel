package cc.thonly.wikikernel.plugin.depdemo;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.depdemo.hook.DepDemoAopHook;
import cc.thonly.wikikernel.plugin.depdemo.hook.DepDemoHeadProvider;

import java.util.List;

/**
 * 插件平台能力演示（**测试用插件**，不对应 MediaWiki 扩展）：
 * <ol>
 *     <li>{@code plugin.requiresCore=>=0.0.1}：core 版本约束（不满足时本插件跳过启动 + WARN）；</li>
 *     <li>{@code plugin.clientRequires=comments}：前端资源依赖（comments 的 head 片段先注入；
 *         缺失时页面留 HTML 注释 + WARN，插件照常启动）；</li>
 *     <li>{@link CoreAopHook}：对 core 的 Bean（{@code SiteSettingsService#get}）加切面并计数
 *         —— 证明「插件对 core / common 做 AOP」在真实实例上生效；</li>
 *     <li>{@link HtmlHeadProvider}：站点级 head 片段，用于验证客户端注入顺序。</li>
 * </ol>
 * 插件只依赖 {@code common}（core 的类名以字符串出现），因此仍满足 {@code checkPluginDependencies}。
 */
public class DepDemoPlugin implements WikiPlugin {

    public static final String ID = "dep-demo";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "DepDemo", "0.0.1",
                "Platform capability demo: CoreAopHook advisors on core beans + plugin.clientRequires head ordering",
                List.of(), ">=0.0.1", List.of("comments"));
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new DepDemoMessages());
        context.register(HtmlHeadProvider.class, new DepDemoHeadProvider());
        context.register(CoreAopHook.class, new DepDemoAopHook());
        context.logger().log(System.Logger.Level.INFO,
                "已注册 CoreAopHook（统计 core SiteSettingsService#get 调用）与站点级 head 片段");
    }
}
