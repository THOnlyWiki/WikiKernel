package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Math 扩展移植（对应 MediaWiki 的 {@code Math}，原为 core 内置功能）：
 * <ul>
 *     <li>{@code <math>TeX</math>}（{@code display="block"} 独立公式）与 {@code <chem>…</chem>}（mhchem 化学式）
 *     生成占位（{@link MathTag}），前端脚本用 KaTeX 渲染；</li>
 *     <li>{@code /plugins/math/math.js} 懒加载 core 内置的 KaTeX（{@code /vendor/katex/…}），
 *     由 {@link MathHeadProvider} 在功能开启时注入。</li>
 * </ul>
 * 兼容性：沿用 core 的功能开关 {@code features.math}（通过 {@link SiteConfig} 读取）；
 * 关闭时按原文转义；空公式提示用消息键 {@code math.empty}
 * （文案在插件自己的 {@code i18n/messages*.properties} 里，见 {@link MathMessages}）。
 */
public class MathPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("math", "Math", "0.1.0",
                "Renders <math> / <chem> formulas with KaTeX (ported from MediaWiki Math)");
    }

    @Override
    public void onStart(PluginContext context) {
        MathTag math = new MathTag(siteConfig, messageSource, false);
        MathTag chem = new MathTag(siteConfig, messageSource, true);
        context.register(MessageBundle.class, new MathMessages());
        context.register(ParserTag.class, math);
        context.register(ParserTag.class, chem);
        context.register(HtmlHeadProvider.class, new MathHeadProvider(siteConfig));
        context.logger().log(System.Logger.Level.INFO,
                siteConfig.getBoolean("features.math", true)
                        ? "已启用 <math> / <chem>（KaTeX 前端渲染）"
                        : "Math 已关闭（features.math=false），标签按原文显示");
    }
}
