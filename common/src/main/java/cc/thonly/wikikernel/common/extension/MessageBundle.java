package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.Map;

/**
 * 扩展点：插件消息包（界面文案），注册后可用 {@code #{键}}（模板）或 core 的 {@code MessageSource} 解析。
 * <p>
 * core 的消息优先；插件消息按语言回退（如 {@code zh-CN → zh → en → 任意}）。
 * 键名建议加插件前缀（如 {@code inputBox.*} 是 core 的，插件可用 {@code input-box.*}）。
 */
public interface MessageBundle extends Extension {

    /** 语言代码（{@code zh} / {@code en} / {@code zh-CN}…）→ 消息键 → 文案。 */
    Map<String, Map<String, String>> messages();
}
