package cc.thonly.wikikernel.plugin.flashplayer;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：Flash 播放器（Ruffle）的界面文案。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 键与迁移前 core 的 {@code flashPlayer.*} 一致（core 里暂时还有同名键，core 优先）；
 * 前端脚本的文案由 {@link FlashPlayerHeadProvider} 经 {@code window.WIKI_MESSAGES} 注入；
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class FlashPlayerMessages extends ResourceMessageBundle {
}
