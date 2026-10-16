package cc.thonly.wikikernel.plugin.echo;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * Echo 插件的消息包：从插件自己 jar 里的 {@code i18n/messages*.properties} 读（默认语言 zh，
 * 另带 {@code messages_en.properties}），基名与默认语言用 {@link ResourceMessageBundle} 的约定。
 * <p>
 * 键前缀 {@code echo.*}：core 的 {@code MessageSource} 先查 core 再查各插件消息包，
 * 所以与 core / 其它插件撞键时会以 core 优先——Echo 的键都带 {@code echo.} 前缀避免冲突。
 */
public final class EchoMessages extends ResourceMessageBundle {
}