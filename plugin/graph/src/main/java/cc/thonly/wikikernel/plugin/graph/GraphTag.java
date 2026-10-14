package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;

/**
 * {@code <graph>Vega 规范</graph>}（仿 MediaWiki Graph 扩展的 {@code <graph>} 标签）：
 * 服务端只校验与转义，前端用本地内置的 Vega / Vega-Lite / vega-embed 渲染。
 * <p>
 * 关闭时（{@code features.graph=false}）按原文转义显示，与迁移前的 core 行为一致。
 */
public class GraphTag implements ParserTag {

    private final GraphSupport support;

    public GraphTag(GraphSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "graph";
    }

    /** 规范是 JSON 原文，不参与模板展开（与迁移前的 core 行为一致）。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        if (!support.enabled()) {
            String attributes = call.attributes().isEmpty() ? "" : " " + rawAttributes(call);
            return "<p>" + GraphSupport.escape("<graph" + attributes + ">" + call.body() + "</graph>") + "</p>";
        }
        String html = support.placeholder(rawAttributes(call), call.body());
        return html != null ? html : support.invalidSpec();
    }

    /** 还原标签属性文本（{@code key="value"} 形式），交给 {@link GraphSupport#placeholder} 解析。 */
    private static String rawAttributes(ParserTagCall call) {
        StringBuilder text = new StringBuilder();
        call.attributes().forEach((key, value) -> {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(key).append("=\"").append(value == null ? "" : value.replace("\"", "&quot;")).append('"');
        });
        return text.toString();
    }
}
