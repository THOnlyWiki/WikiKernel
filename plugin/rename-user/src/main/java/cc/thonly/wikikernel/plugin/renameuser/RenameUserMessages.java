package cc.thonly.wikikernel.plugin.renameuser;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：重命名用户文案（键名与迁移前 core 的 {@code renameUser.*} / {@code special.renameUser} /
 * {@code log.typeName.renameuser} / {@code log.entry.renameuser} / {@code log.renameuser.*} 完全一致，
 * core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * {@code permission.group.renameuser}（「重命名用户」用户组的显示名）与
 * {@code tools.renameUser}（用户页「工具」侧栏入口）仍属于 core，不在这里重复。
 */
public class RenameUserMessages extends ResourceMessageBundle {
}