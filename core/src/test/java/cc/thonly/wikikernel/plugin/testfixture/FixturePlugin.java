package cc.thonly.wikikernel.plugin.testfixture;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试替身插件入口（core 测试用，**不在 plugin/ 下**）：
 * <p>
 * 插件依赖测试会为它生成若干「只有描述文件」的 jar（同一个入口类 + 不同的
 * {@code plugin.requires} / {@code plugin.requiresCore} / {@code plugin.clientRequires}），
 * 因此：
 * <ul>
 *     <li>{@link #START_ORDER} 记录真实的启动顺序，用来断言「被依赖的插件先启动」；</li>
 *     <li>{@code onStart} 注册一个站点级 {@link HtmlHeadProvider}，片段内容就是插件 id，
 *         用来断言 {@code <head>} 的注入顺序。</li>
 * </ul>
 */
public class FixturePlugin implements WikiPlugin {

    /** 插件启动顺序（进程内共享：插件子容器与测试用的是同一个类加载器）。 */
    public static final List<String> START_ORDER = new CopyOnWriteArrayList<>();

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("fixture", "Fixture", "1.0.0", "core 测试替身插件");
    }

    @Override
    public void onStart(PluginContext context) {
        START_ORDER.add(context.pluginId());
        context.register(HtmlHeadProvider.class, new FixtureHeadProvider(context.pluginId()));
    }

    /** 站点级 head 片段：{@code <!-- head:<插件id> -->}。 */
    public static final class FixtureHeadProvider implements HtmlHeadProvider {

        private final String pluginId;

        FixtureHeadProvider(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public boolean siteWide() {
            return true;
        }

        @Override
        public List<String> headItems(HeadContext context) {
            return List.of(marker(this.pluginId));
        }

        public static String marker(String pluginId) {
            return "<!-- head:" + pluginId + " -->";
        }
    }
}
