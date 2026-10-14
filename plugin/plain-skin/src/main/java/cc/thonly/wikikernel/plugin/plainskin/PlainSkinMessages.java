package cc.thonly.wikikernel.plugin.plainskin;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：Plain 皮肤插件的界面文案（插件名 / 描述、皮肤名 / 描述、安装向导文案）。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 皮肤名 / 说明按 core 的约定用 {@code skin.<id>.name|description} 键（见 core 的 SkinRegistry）；
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class PlainSkinMessages extends ResourceMessageBundle {
}
