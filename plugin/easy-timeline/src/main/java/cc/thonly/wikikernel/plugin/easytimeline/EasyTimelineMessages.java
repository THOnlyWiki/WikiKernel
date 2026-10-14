package cc.thonly.wikikernel.plugin.easytimeline;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：时间轴（EasyTimeline）的界面文案。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 键名与迁移前一致（{@code timeline.invalid} 是原 core 的键，core 里有同名键时 core 优先）；
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class EasyTimelineMessages extends ResourceMessageBundle {
}
