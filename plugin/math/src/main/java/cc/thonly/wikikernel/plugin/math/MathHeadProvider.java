package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;

import java.util.List;

/**
 * {@code <math>} / {@code <chem>} 的前端脚本（懒加载 KaTeX）：
 * 功能开启时注入 {@code /plugins/math/math.js}（脚本内部再从 core 的 {@code /vendor/katex/…} 懒加载 KaTeX）。
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
    public List<String> headItems(HeadContext context) {
        if (!siteConfig.getBoolean("features.math", true)) {
            return List.of();
        }
        return List.of(
                // 鑷繁澹版槑鍓嶇寮€鍏筹紙core 涓嶅啀鐭ラ亾杩欎釜鍔熻兘锛夛細鑴氭湰璇?window.WIKI_CONFIG.math
                "<script>window.WIKI_CONFIG = Object.assign(window.WIKI_CONFIG || {}, {math: true});</script>",
                "<script defer src=\"/plugins/math/math.js\"></script>");
    }
}
