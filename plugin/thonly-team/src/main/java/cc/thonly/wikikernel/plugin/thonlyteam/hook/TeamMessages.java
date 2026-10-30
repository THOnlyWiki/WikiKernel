package cc.thonly.wikikernel.plugin.thonlyteam.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 组队文案。
 *
 * <p>文案在插件自己的资源文件里，键名与 {@code TeamApiController} 里用的一致：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 *
 * <p>**必须注册成 {@code MessageBundle} 扩展**，否则 core 的 PluginMessageSource
 * 查不到这些键，接口会直接返回键名本身（比如 {@code team.notFound}）。
 */
public class TeamMessages extends ResourceMessageBundle {
}
