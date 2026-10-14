package cc.thonly.wikikernel.plugin.inputbox;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：InputBox（{@code <inputbox>}）的界面文案。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 表单按钮的文字沿用 core 的通用键 {@code common.search} / {@code special.createPage}
 * （core 的消息优先，插件不再自带中文兜底文案）；
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class InputBoxMessages extends ResourceMessageBundle {
}
