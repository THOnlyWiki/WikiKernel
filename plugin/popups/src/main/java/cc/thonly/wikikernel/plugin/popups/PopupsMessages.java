package cc.thonly.wikikernel.plugin.popups;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：页面预览（Popups / Page Previews）的界面文案。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 前端脚本（{@code /plugins/popups/popups.js}）的文案由 {@link PopupsHeadProvider} 经
 * {@code window.WIKI_MESSAGES} 注入；漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class PopupsMessages extends ResourceMessageBundle {
}
