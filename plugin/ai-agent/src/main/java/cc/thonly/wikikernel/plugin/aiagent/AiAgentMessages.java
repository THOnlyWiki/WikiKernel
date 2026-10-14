package cc.thonly.wikikernel.plugin.aiagent;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：AI 代理文案（键名与迁移前 core 的 {@code aiAgent.*}（除水印标题）/ {@code preferences.ai.*} /
 * {@code preferences.tab.ai} / {@code settings.aiVendors*} / {@code settings.aiAgentAllowNormal*} 完全一致，
 * core 里已删除同名键）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 仍属于 core、不在这里重复的键：{@code settings.aiAgent} / {@code settings.aiAgentHint} /
 * {@code setup.aiAgent} / {@code setup.aiAgentHint}（站点设置与 /init 的功能开关文案）与
 * {@code aiAgent.watermarkTitle}（core 渲染词条底部隐藏水印时的 title）。
 */
public class AiAgentMessages extends ResourceMessageBundle {
}
