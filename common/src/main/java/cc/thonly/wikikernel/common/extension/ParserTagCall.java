package cc.thonly.wikikernel.common.extension;

import java.util.List;
import java.util.Map;

/**
 * 扩展标签调用信息（{@code <tag …>内容</tag>}）。
 */
public interface ParserTagCall {

    /** 本次渲染上下文（页面属性 / 渲染变量可写在这里）。 */
    WikiRenderContext context();

    /** 标签名（小写）。 */
    String tag();

    /** HTML 属性（原样，未展开）。 */
    Map<String, String> attributes();

    /**
     * 标签内容：{@code ParserTag#expandBody()} 为 true 时已随正文展开（模板参数 / 模板已替换），
     * 否则是原始文本（如 {@code <templatedata>} 的 JSON）。
     */
    String body();

    /** 按当前上下文展开一段 wikitext（大部分标签不需要，内容已经展开）。 */
    String expand(String wikitext);
}
