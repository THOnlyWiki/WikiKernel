package cc.thonly.wikikernel.plugin.thonlyteam;

import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeam;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeamMember;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeamReport;

import java.util.List;

/**
 * 组队跑展：在活动下面发起组队帖（拼车 / 拼房 / 同行 / 摊位 / 拍摄）。
 *
 * <p>三条安全底线，都是站长明确要求的：
 * <ul>
 *     <li>联系方式只在**加入之后**可见，列表接口永远不返回；</li>
 *     <li>举报入口：只记录 + 写通用日志，不自动处置，避免误伤；</li>
 *     <li>站长可一键解散（软删除，数据留着可追溯）。</li>
 * </ul>
 *
 * <p>加入方式由团长选：{@code instant} 立即进入 / {@code approve} 需要批准。
 *
 * <p>表 {@code wiki_team} / {@code wiki_team_member} / {@code wiki_team_report}
 * 属于本插件，在 {@link #schemaEntities()} 里声明，由 core 的 Auto-DDL 建表。
 */
public class TeamPlugin implements WikiPlugin {

    /** 功能开关（站点设置 → 插件设置） */
    public static final String FEATURE_KEY = "features.team";

    /** 日志类型 */
    public static final String LOG_TEAM_CREATE = "teamcreate";
    public static final String LOG_TEAM_DELETE = "teamdelete";
    public static final String LOG_TEAM_REPORT = "teamreport";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                "thonly-team",
                "Touhou Team",
                "1.0.0",
                "组队跑展：按活动发起的组队帖，联系方式加入后可见，支持审批与举报",
                List.of(),
                "",
                List.of(),
                FEATURE_KEY,
                true);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(LogType.class, new TeamLogType(LOG_TEAM_CREATE));
        context.register(LogType.class, new TeamLogType(LOG_TEAM_DELETE));
        context.register(LogType.class, new TeamLogType(LOG_TEAM_REPORT));
    }

    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiTeam.class, WikiTeamMember.class, WikiTeamReport.class);
    }

    /** 日志类型：展示名走消息键 {@code log.typeName.<id>} */
    record TeamLogType(String id) implements LogType {
    }
}
