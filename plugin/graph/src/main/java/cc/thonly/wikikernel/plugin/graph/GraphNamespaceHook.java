package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;

/**
 * {@code 图表:} / {@code Graph:} 命名空间的**整页图**（仿 MediaWiki Graph 扩展的 Graph 命名空间）：
 * 页面正文本身就是 Vega / Vega-Lite 规范，阅读时整页渲染成图，编辑 / 历史页签照旧。
 * <p>
 * 关闭时返回 {@code null} = 不认领，页面按普通 wikitext 渲染（与迁移前的 core 行为一致）。
 */
public class GraphNamespaceHook implements PageDisplayHook {

    private final GraphSupport support;

    public GraphNamespaceHook(GraphSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String renderBody(PageDisplayContext context) {
        if (!support.enabled() || !GraphSupport.isGraphTitle(context.title())) {
            return null;
        }
        String html = support.pagePlaceholder(context.content(), null);
        return html != null ? html : support.invalidSpec();
    }
}
