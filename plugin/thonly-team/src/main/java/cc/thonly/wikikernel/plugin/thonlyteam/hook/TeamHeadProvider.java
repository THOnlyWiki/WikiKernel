package cc.thonly.wikikernel.plugin.thonlyteam.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.thonlyteam.TeamPlugin;

import java.util.List;

/**
 * 组队的样式与脚本。站点级注入（脚本自己判断页面里有没有 {@code .wiki-team}，
 * 没有就直接返回，开销可以忽略）。
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
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (siteConfig != null && !siteConfig.getBoolean(TeamPlugin.FEATURE_KEY, true)) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/thonly-team/team.css\">",
                "<script defer src=\"/plugins/thonly-team/team.js\"></script>");
    }
}
