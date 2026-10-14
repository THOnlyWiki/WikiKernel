package cc.thonly.wikikernel.plugin.notitle;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;

import java.util.List;

/**
 * {@code __NOTITLE__} 行为开关：写页面属性 {@code notitle=1}，
 * WikiKernel 的皮肤布局读该属性隐藏 {@code h1.firstHeading}（与 MediaWiki NoTitle 的 CSS 效果一致）。
 * 别名来自 NoTitle 扩展的中文本地化（{@code NoTitle.i18n.magic.php}）。
 */
public class NoTitleSwitch implements BehaviorSwitch {

    /** 页面属性名（core 的 {@code WikiPageController.renderView} 读取它设置 hideTitle）。 */
    public static final String PROPERTY = "notitle";

    @Override
    public String name() {
        return "NOTITLE";
    }

    @Override
    public List<String> aliases() {
        return List.of("无标题");
    }

    @Override
    public void apply(WikiRenderContext context) {
        context.setProperty(PROPERTY, "1");
    }
}
