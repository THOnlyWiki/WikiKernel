package cc.thonly.wikikernel.plugin.moderation;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：审核文案（键名与迁移前 core 的 {@code moderation.*} / {@code special.moderation} /
 * {@code log.*moderation*} / {@code recentChanges.logName.moderation*} /
 * {@code files.uploadPending} / {@code files.deletePending} 完全一致，core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * {@code setup.moderation*} / {@code settings.moderation*}（安装向导 / 站点设置的开关与说明）
 * 与 {@code permission.group.moderatorexempt}（免审查用户组的显示名）仍属于 core，不在这里重复。
 */
public class ModerationMessages extends ResourceMessageBundle {
}
