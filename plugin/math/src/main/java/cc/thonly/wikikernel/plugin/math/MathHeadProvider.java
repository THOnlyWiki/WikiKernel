package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;

import java.util.List;

/**
 * {@code <math>} / {@code <chem>} 的前端脚本（懒加载 KaTeX）：
 * 功能开启时注入 {@code /plugins/math/math.js}（脚本内部再从 core 的 {@code /vendor/katex/…} 懒加载 KaTeX）。
 * <p>
 * {@link #siteWide()} = true：前端开关（{@code window.WIKI_CONFIG.math}）与脚本要在 core 初始化
 * {@code window.WIKI_CONFIG} 之后注入——站点级片段才会插到那里之后，否则开关会被覆盖、
 * 脚本提前退出（公式只剩 TeX 源码回退）；编辑预览等页面的公式也依赖它渲染。
 */
public class MathHeadProvider implements HtmlHeadProvider {

    private final SiteConfig siteConfig;

    public MathHeadProvider(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!siteConfig.getBoolean("features.math", true)) {
            return List.of();
        }
        return List.of(
                // 自己声明前端开关（core 不再知道这个功能）：脚本读 window.WIKI_CONFIG.math
                "<script>window.WIKI_CONFIG = Object.assign(window.WIKI_CONFIG || {}, {math: true});</script>",
                "<script defer src=\"/plugins/math/math.js\"></script>");
    }
}
