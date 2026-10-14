package cc.thonly.wikikernel.plugin.inputbox;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;

import java.util.List;

/**
 * {@code <inputbox>} 的前端脚本（prefix / preload / editintro 组装目标标题）：
 * 功能开启时注入 {@code /plugins/input-box/inputbox.js}（由 core 的插件资源端点提供）。
 */
public class InputBoxHeadProvider implements HtmlHeadProvider {

    private final SiteConfig siteConfig;

    public InputBoxHeadProvider(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!siteConfig.getBoolean("features.input-box", true)) {
            return List.of();
        }
        return List.of("<script defer src=\"/plugins/input-box/inputbox.js\"></script>");
    }
}
