package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.util.List;

/**
 * 图形前端资源：core 内置的 Vega / Vega-Lite / vega-embed（{@code /vendor/vega/}，不走 CDN）
 * 与本插件的 {@code /plugins/graph/graph.js}。
 * <p>
 * {@link #siteWide()} = true：除词条阅读视图外，编辑预览（{@code ?action=edit} 里的
 * {@code <graph>} 预览）等页面也要能渲染图形。
 */
public class GraphHeadProvider implements HtmlHeadProvider {

    private final GraphSupport support;

    public GraphHeadProvider(GraphSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 400;
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
                "<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                        + "window.WIKI_CONFIG.graph = true;"
                        + "window.WIKI_CONFIG.graphRuntimeError = \""
                        + GraphSupport.escapeJson(support.message("graph.runtimeError"))
                        + "\";"
                        + "window.WIKI_CONFIG.graphVegaMissing = \""
                        + GraphSupport.escapeJson(support.message("graph.vegaMissing"))
                        + "\";</script>",
                "<script defer src=\"/vendor/vega/vega.min.js\"></script>",
                "<script defer src=\"/vendor/vega/vega-lite.min.js\"></script>",
                "<script defer src=\"/vendor/vega/vega-embed.min.js\"></script>",
                "<script defer src=\"/plugins/graph/graph.js\"></script>");
    }
}
