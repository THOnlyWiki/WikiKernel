package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：浏览器指纹登录的界面文案。
 * <p>
 * 文案在插件自己的资源文件里（不再集中到 core 的 {@code messages.properties}）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 漏翻的键由 core 的消息源回退成翻译键本身（不会显示中文）。
 * <p>
 * 必须包含 {@code plugin.fingerprint-login.name} / {@code plugin.fingerprint-login.description}
 * （core 的 {@code PluginI18n} 用它们本地化插件名与描述）。
 */
public class FingerprintLoginMessages extends ResourceMessageBundle {
}
