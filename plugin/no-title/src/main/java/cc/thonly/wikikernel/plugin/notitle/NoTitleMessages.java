package cc.thonly.wikikernel.plugin.notitle;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：NoTitle 的界面文案（插件名 / 描述）。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 行为开关的名字与别名（{@code __NOTITLE__} / {@code __无标题__}）是**页面语法**，不随界面语言变化，
 * 仍然写死在 {@link NoTitleSwitch} 里；漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class NoTitleMessages extends ResourceMessageBundle {
}
