package cc.thonly.wikikernel.plugin.thonlyteam.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.thonlyteam.TeamPlugin;

import java.util.List;

/**
 * 组队的样式与脚本。
 *
 * <p>**页面级注入**：只有写了 {@code <team />} 的页面（属性 {@link TeamTag#PROPERTY}）才加载，
 * 其它页面不注入。这样既省流量，也保证安全提醒弹窗只可能出现在组队页面。
 */
public class TeamHeadProvider implements HtmlHeadProvider {

    private final SiteConfig siteConfig;

    public TeamHeadProvider(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    @Override
    public int order() {
        return 660;
    }

    @Override
    public boolean siteWide() {
        return false;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (siteConfig != null && !siteConfig.getBoolean(TeamPlugin.FEATURE_KEY, true)) {
            return List.of();
        }
        // 页面里没有 <team /> 就不注入
        if (context == null || !context.properties().containsKey(TeamTag.PROPERTY)) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/thonly-team/team.css\">",
                "<script defer src=\"/plugins/thonly-team/team.js\"></script>");
    }
}
