package cc.thonly.wikikernel.plugin.thonlyteam.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeam;
import cc.thonly.wikikernel.plugin.thonlyteam.service.TeamStore;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:组队管理}：站长看所有队伍和举报，一键解散。
 *
 * <p>权限：对「线下活动速查表」有 delete 权限（默认管理员）才进得来。
 * 解散走 {@code POST /api/thonly-team/delete}，软删除，数据留着可追溯。
 *
 * <p>直接 {@code render} 返回 HTML，不搞模板——就一张表。
 */
public class TeamAdminPage implements SpecialPage {

    private static final String ACL_PAGE = "线下活动速查表";

    private final TeamStore store;
    private final AccessCheck access;
    private final CurrentUser currentUser;

    public TeamAdminPage(TeamStore store, AccessCheck access, CurrentUser currentUser) {
        this.store = store;
        this.access = access;
        this.currentUser = currentUser;
    }

    @Override
    public String name() {
        return "组队管理";
    }

    @Override
    public String category() {
        return SpecialPageCategories.OTHER;
    }

    @Override
    public String render(SpecialPageRequest request) {
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return notice("请先登录。");
        }
        if (!access.can(AccessAction.DELETE, ACL_PAGE, viewer)) {
            return notice("只有管理员能进这个页面。");
        }

        List<WikiTeam> teams = store.listAll();
        Map<Long, Integer> reports = store.reportCounts();

        StringBuilder html = new StringBuilder();
        html.append("<p class=\"team-admin-hint\">共 ").append(teams.size())
                .append(" 支队伍。解散是软删除，数据保留可追溯。</p>");

        if (teams.isEmpty()) {
            html.append("<p class=\"team-admin-empty\">还没有任何队伍。</p>");
        } else {
            html.append("<table class=\"wikitable team-admin-table\"><thead><tr>")
                    .append("<th>ID</th><th>活动</th><th>队伍</th><th>团长</th>")
                    .append("<th>人数</th><th>状态</th><th>举报</th><th>操作</th>")
                    .append("</tr></thead><tbody>");
            for (WikiTeam team : teams) {
                int joined = store.countJoined(team.getId());
                int reportCount = reports.getOrDefault(team.getId(), 0);
                html.append("<tr>")
                        .append("<td>").append(team.getId()).append("</td>")
                        .append("<td>").append(esc(team.getEventName())).append("</td>")
                        .append("<td>").append(esc(team.getTitle())).append("</td>")
                        .append("<td>").append(esc(team.getOwnerName())).append("</td>")
                        .append("<td>").append(joined).append("/")
                        .append(team.getCapacity() == null ? "?" : team.getCapacity()).append("</td>")
                        .append("<td>").append(statusLabel(team.getStatus())).append("</td>")
                        .append("<td>").append(reportCount > 0
                                ? "<strong class=\"team-admin-report\">" + reportCount + "</strong>" : "0")
                        .append("</td>")
                        .append("<td>");
                if (TeamStore.STATUS_DELETED.equals(team.getStatus())) {
                    html.append("—");
                } else {
                    html.append("<button type=\"button\" class=\"team-admin-delete\" data-id=\"")
                            .append(team.getId()).append("\">解散</button>");
                }
                html.append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        html.append("<script>(function(){")
                .append("document.querySelectorAll('.team-admin-delete').forEach(function(btn){")
                .append("btn.addEventListener('click',function(){")
                .append("if(!confirm('确定解散这支队伍？'))return;")
                .append("var body=new URLSearchParams();body.append('id',btn.dataset.id);")
                .append("fetch('/api/thonly-team/delete',{method:'POST',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded',")
                .append("'X-Requested-With':'XMLHttpRequest'},body:body.toString()})")
                .append(".then(function(r){return r.json()})")
                .append(".then(function(res){alert(res.message||'已处理');")
                .append("if(res.code===200)location.reload();})")
                .append(".catch(function(){alert('网络异常')});")
                .append("});});})();</script>");
        return html.toString();
    }

    private static String statusLabel(String status) {
        if (TeamStore.STATUS_DELETED.equals(status)) {
            return "已解散";
        }
        if (TeamStore.STATUS_CLOSED.equals(status)) {
            return "已关闭";
        }
        return "招募中";
    }

    private static String notice(String text) {
        return "<p class=\"team-admin-empty\">" + esc(text) + "</p>";
    }

    private static String esc(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
