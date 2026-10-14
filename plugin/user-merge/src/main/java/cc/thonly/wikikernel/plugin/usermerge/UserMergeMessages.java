package cc.thonly.wikikernel.plugin.usermerge;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：用户合并文案（键名与迁移前 core 的 {@code userMerge.*} / {@code special.userMerge} /
 * {@code log.typeName.usermerge} / {@code log.entry.usermerge} / {@code log.usermerge.*} 完全一致，
 * core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * {@code tools.userMerge}（用户页「工具」侧栏入口）仍属于 core，不在这里重复。
 */
public class UserMergeMessages extends ResourceMessageBundle {
}