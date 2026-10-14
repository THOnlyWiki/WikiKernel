package cc.thonly.wikikernel.plugin.plainskin;

import cc.thonly.wikikernel.common.extension.Skin;

/**
 * Plain 皮肤（本插件注册的 {@code plain} 皮肤）：居中卡片布局、无侧栏。
 * 名称 / 说明由 core 按消息键 {@code skin.plain.name} / {@code skin.plain.description} 取本地化文案
 * （见 {@link PlainSkinMessages}）；这里的默认值是**技术名 / 英文兜底**，
 * core 只在消息键缺失且默认值不是中文时才使用它（中文默认值会被换成翻译键）。
 */
public class PlainSkin implements Skin {

    @Override
    public String id() {
        return "plain";
    }

    @Override
    public String displayName() {
        return "Plain";
    }

    @Override
    public String description() {
        return "Minimal skin provided by the plugin: centered card layout without a sidebar; layout and CSS ship inside the plugin jar";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public int order() {
        return 50;
    }
}
