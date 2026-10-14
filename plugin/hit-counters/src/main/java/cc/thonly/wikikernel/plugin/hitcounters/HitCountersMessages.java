package cc.thonly.wikikernel.plugin.hitcounters;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：页脚浏览量、{@code 特殊:最多浏览} 与安装向导的文案。
 * <p>
 * 文案在插件自己的资源文件里（不再写在 Java 代码里）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 */
public class HitCountersMessages extends ResourceMessageBundle {
}
