package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：插件提供的解析器函数（{@code {{#name:...}}}，仿 MediaWiki {@code setFunctionHook}）。
 * <p>
 * 示例：插件注册 {@code name() = "seo"} 后，页面里 {@code {{#seo:title=…}}} 会调用 {@link #invoke}。
 * 未注册的名字仍按现有行为原样保留；内置（{@code #if/#ifeq/#switch/#widget/#invoke}）优先。
 */
public interface ParserFunction extends Extension {

    /** 函数名（不含 {@code #}，大小写不敏感）。 */
    String name();

    /** 别名（可选，大小写不敏感）。 */
    default java.util.List<String> aliases() {
        return java.util.List.of();
    }

    /** 执行并返回文本（会按 wikitext 继续展开；需要输出可信 HTML 时请让调用方约定占位）。 */
    String invoke(ParserFunctionCall call);
}
