package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Graph 扩展移植（对应 MediaWiki 的 {@code Graph} 扩展，去掉 Graphoid 渲染服务）：
 * <ul>
 *     <li>{@code <graph>Vega 规范</graph>} 标签（{@link GraphTag}）；</li>
 *     <li>{@code 图表:} / {@code Graph:} 命名空间的**整页图**（{@link GraphNamespaceHook}）；</li>
 *     <li>{@code /plugins/graph/graph.js} + core 内置的 Vega / Vega-Lite / vega-embed
 *     （{@link GraphHeadProvider}，本地渲染，不走 CDN、不需要 headless 浏览器）。</li>
 * </ul>
 * 开关沿用 core 的功能开关 {@code features.graph}（默认开启；{@code /init}／站点设置／LocalSettings 都可改）；
 * 关闭时 {@code <graph>} 按原文转义显示、{@code 图表:} 页面按普通页面渲染。
 */
public class GraphPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.graph";

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("graph", "Graph", "1.0.0",
                "Graph tag and full-page graphs in the Graph namespace with local Vega / Vega-Lite rendering");
    }

    @Override
    public void onStart(PluginContext context) {
        GraphSupport support = new GraphSupport(siteConfig, messageSource);
        context.register(MessageBundle.class, new GraphMessages());
        context.register(ParserTag.class, new GraphTag(support));
        context.register(PageDisplayHook.class, new GraphNamespaceHook(support));
        context.register(HtmlHeadProvider.class, new GraphHeadProvider(support));
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用图形：<graph> 标签与「图表:」整页图（Vega 由 core 的 /vendor/vega/ 本地加载）"
                : "图形已关闭（features.graph=false）：<graph> 按原文显示，图表命名空间按普通页面渲染");
    }

    /**
     * 插件消息：{@code <graph>} 标签与图表命名空间的提示文案。
     * <p>
     * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
     * <pre>
     * src/main/resources/i18n/messages.properties     中文（默认语言）
     * src/main/resources/i18n/messages_en.properties  英文
     * </pre>
     * 键与迁移前 core 的 {@code graph.*} 一致；漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
     */
    static final class GraphMessages extends ResourceMessageBundle {
    }
}
