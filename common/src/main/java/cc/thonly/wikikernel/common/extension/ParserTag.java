package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：插件提供的解析器标签（{@code <tag>…</tag>}，仿 MediaWiki {@code setHook}）。
 * <p>
 * 示例：{@code <templatedata>} / {@code <seo>…</seo>}。
 * 未注册的标签仍按现有行为（HTML 白名单 / 转义）处理。
 */
public interface ParserTag extends Extension {

    /** 标签名（不含尖括号，大小写不敏感，如 {@code templatedata}）。 */
    String name();

    /**
     * 标签内容是否先随正文展开（模板参数 / 模板 / 解析器函数）：
     * <ul>
     *     <li>{@code true}（默认，如 {@code <seo>}）：内容按普通正文展开后再交给插件；</li>
     *     <li>{@code false}（如 {@code <templatedata>}）：内容按原文交给插件（JSON 不会被误展开）。</li>
     * </ul>
     */
    default boolean expandBody() {
        return true;
    }

    /** 返回替换标签的 HTML（可信，不经过 HTML 白名单；返回 null 视为空串）。 */
    String render(ParserTagCall call);

    /** 支持的标签名列表（便于可视化编辑器登记整块占位；默认只有 {@link #name()}）。 */
    default java.util.List<String> names() {
        return java.util.List.of(name());
    }
}
