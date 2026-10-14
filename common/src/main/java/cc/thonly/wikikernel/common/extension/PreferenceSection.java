package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：参数设置（{@code 特殊:参数设置}）里由插件贡献的一个**分区（标签页）**，仿 MediaWiki 的
 * {@code PreferencesGetLegend} / {@code GetPreferences} 钩子。
 * <p>
 * core 在渲染参数设置页时按 {@link #order()} 升序收集所有注册的分区：
 * 用 {@link #labelKey()} 取标签名，把 {@link #html(PreferenceContext)} 返回的 HTML 放进该分区面板。
 * 表单提交由插件自己负责（在 HTML 里写上自己的表单 + 脚本，POST 到插件自己的接口），
 * 用户偏好的读写用 {@code common.service.UserSettings}。
 */
public interface PreferenceSection extends Extension {

    /** 分区 id（同时用作 HTML 锚点，如 {@code gadgets}；建议与插件 id 对应，避免冲突）。 */
    String id();

    /** 标签名的消息键（core 的 MessageSource 解析，插件消息可用）；缺失时用 {@link #fallbackLabel()}。 */
    String labelKey();

    /** 标签名的兜底文案（消息键缺失时显示）。 */
    default String fallbackLabel() {
        return id();
    }

    /** 执行顺序，值越小越靠前。 */
    default int order() {
        return 0;
    }

    /** 分区面板里的 HTML（表单 / 复选框 / 说明）；返回 null / 空串 = 该分区不显示。 */
    String html(PreferenceContext context);
}
