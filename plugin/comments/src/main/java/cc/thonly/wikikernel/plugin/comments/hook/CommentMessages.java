package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：评论区文案（键与迁移前 core 的 {@code comment.*} 一致，core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 */
public class CommentMessages extends ResourceMessageBundle {
}
