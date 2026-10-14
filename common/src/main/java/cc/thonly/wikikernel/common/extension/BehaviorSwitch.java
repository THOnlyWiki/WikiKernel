package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 扩展点：插件提供的行为开关（{@code __NAME__}，仿 MediaWiki double-underscore behavior switches）。
 * <p>
 * 开关会在正文里被剥离，{@link #apply} 可往渲染上下文写页面属性（如 {@code notitle}）。
 * 内置开关（{@code __NOTOC__ / __NOINDEX__ / …}）优先。
 */
public interface BehaviorSwitch extends Extension {

    /** 开关名（不含两侧下划线，如 {@code NOTITLE}；按大写比较）。 */
    String name();

    /** 别名（可选，如中文 {@code 无标题}）。 */
    default List<String> aliases() {
        return List.of();
    }

    /** 页面里出现该开关时调用（可写 {@link WikiRenderContext#setProperty}）。 */
    void apply(WikiRenderContext context);
}
