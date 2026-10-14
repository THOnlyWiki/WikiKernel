package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：插件提供的魔术字（{@code {{NAME}}}，仿 MediaWiki magic variables）。
 * <p>
 * 内置魔术字（{@code PAGENAME} / {@code NUMBEROFPAGES} / 时间家族等）优先；
 * 名字匹配不区分大小写（统一按大写比较）。
 */
public interface MagicWord extends Extension {

    /** 魔术字名（如 {@code NUMBEROFVIEWS}，不含花括号）。 */
    String name();

    /** 本地化别名（可选，如 {@code 浏览量}）。 */
    default java.util.List<String> aliases() {
        return java.util.List.of();
    }

    /** 返回替换文本（会按 wikitext 继续展开；返回 null 视为空串）。 */
    String value(WikiRenderContext context);
}
