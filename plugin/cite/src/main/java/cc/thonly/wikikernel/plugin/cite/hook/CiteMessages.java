package cc.thonly.wikikernel.plugin.cite.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * Cite 扩展的本地化文案（{@code src/main/resources/i18n/messages.properties} 中文 +
 * {@code messages_en.properties} 英文；键：{@code cite.error.*}）。
 * <p>
 * 插件消息包必须显式注册（core 的 {@code PluginMessageSource} 只聚合注册过的
 * {@code MessageBundle} 扩展点）。
 */
public class CiteMessages extends ResourceMessageBundle {
}
