package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：播放器与图片查看器的文案（键与迁移前 core 的 {@code media.*} / {@code imageViewer.*} 一致，
 * core 里已删除，避免两处维护）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 前端脚本经 {@code window.WIKI_MESSAGES}（{@code MediaViewerHeadProvider} 注入）取这些键。
 */
public class MediaViewerMessages extends ResourceMessageBundle {
}
