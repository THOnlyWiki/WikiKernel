package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：插件提供的 {@code /api.php?action=…} 模块（如 TemplateData 的 {@code action=templatedata}）。
 * <p>
 * core 内置 action 优先；名字未命中内置 action 时按 {@link #action()} 查找插件实现。
 * 返回 Map / List / 标量，由 core 按 {@code format}（json / jsonfm / xml / jsonp）统一序列化。
 */
public interface ActionApiModule extends Extension {

    /** 处理器名（{@code action=} 的值，小写，如 {@code templatedata}）。 */
    String action();

    /** 执行并返回结果；出错请抛 {@link ActionApiException}。 */
    Object handle(ActionApiRequest request);
}
