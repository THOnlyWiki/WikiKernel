package cc.thonly.wikikernel.common.plugin;

/**
 * 扩展点标记接口。
 * <p>
 * 需要跨插件协作的能力必须先在 common 中定义扩展点接口，插件只负责实现并注册。
 * 这样插件之间永远不需要互相依赖，也不会有依赖循环。
 */
public interface Extension {
}
