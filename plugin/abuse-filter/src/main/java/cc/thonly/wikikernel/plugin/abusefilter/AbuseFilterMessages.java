package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：滥用过滤器文案（键名与迁移前 core 的 {@code abusefilter.*} /
 * {@code special.abuseFilter} / {@code special.abuseLog} 完全一致，core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * {@code settings.abuseFilter*} / {@code setup.abuseFilter*} 仍属于 core 的站点设置与安装向导，不在这里重复。
 */
public class AbuseFilterMessages extends ResourceMessageBundle {
}
