package cc.thonly.wikikernel.plugin.confirmedit.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：{@code confirmEdit.*} 文案（**键名与迁移前 core 的 messages.properties 完全相同**）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * core 里同名键已删除，插件注册后由 core 的 {@code PluginMessageSource} 合并解析
 * （验证码字段 HTML 与保存前钩子的拒绝说明都用它）。
 * 站点设置表单自身的标签（{@code settings.confirmEdit*} / {@code setup.confirmEdit*}）属于 core 的界面文案，
 * 仍留在 core 的消息文件里。
 */
public class ConfirmEditMessages extends ResourceMessageBundle {
}
