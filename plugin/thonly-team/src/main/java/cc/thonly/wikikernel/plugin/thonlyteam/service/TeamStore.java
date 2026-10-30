package cc.thonly.wikikernel.plugin.thonlyteam.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeam;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeamMember;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeamReport;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 队伍数据访问。表属于本插件，由 {@code TeamPlugin.schemaEntities()} 声明、
 * core 的 Auto-DDL 建表，读写走 {@link PluginDatabase}。
 *
 * <p>只做存取：谁能建、谁能加、联系方式给谁看，都在 {@code TeamApiController} 里判断。
 */
@Service
public class TeamStore {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_DELETED = "deleted";

    public static final String MEMBER_JOINED = "joined";
    public static final String MEMBER_PENDING = "pending";
    public static final String MEMBER_REJECTED = "rejected";

    private final PluginDatabase database;

    public TeamStore(PluginDatabase database) {
        this.database = database;
    }

    // ==================== 队伍 ====================

    /** 某场活动下的队伍，新建的排前面；已解散的不返回 */
    public List<WikiTeam> listByEvent(int eventYear, int eventId) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_team WHERE event_year = ? AND event_id = ? AND status <> ?"
                        + " ORDER BY id DESC",
                eventYear, eventId, STATUS_DELETED);
        List<WikiTeam> teams = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            teams.add(team(row));
        }
        return teams;
    }

    /** 整年的队伍（eventId 缺省时用，给「组队跑展」总览页） */
    public List<WikiTeam> listByYear(int eventYear) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_team WHERE event_year = ? AND status <> ? ORDER BY id DESC",
                eventYear, STATUS_DELETED);
        List<WikiTeam> teams = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            teams.add(team(row));
        }
        return teams;
    }

    public Optional<WikiTeam> find(long id) {
        return database.queryOne("SELECT * FROM wiki_team WHERE id = ?", id).map(TeamStore::team);
    }

    public long insert(WikiTeam team) {
        LocalDateTime now = LocalDateTime.now();
        team.setCreateAt(now);
        team.setUpdateAt(now);
        return database.insert("INSERT INTO wiki_team"
                        + "(event_year, event_id, event_name, type, title, note, capacity, contact,"
                        + " join_mode, owner_id, owner_name, status, create_at, update_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                team.getEventYear(), team.getEventId(), team.getEventName(), team.getType(),
                team.getTitle(), team.getNote(), team.getCapacity(), team.getContact(),
                team.getJoinMode(), team.getOwnerId(), team.getOwnerName(), team.getStatus(),
                team.getCreateAt(), team.getUpdateAt());
    }

    /** 关闭 / 解散：只改状态，不删数据（站长处理完还能追溯） */
    public void updateStatus(long id, String status, Long operatorId) {
        database.update("UPDATE wiki_team SET status = ?, deleted_by = ?, deleted_at = ?,"
                        + " update_at = ? WHERE id = ?",
                status, operatorId, LocalDateTime.now(), LocalDateTime.now(), id);
    }

    // ==================== 成员 ====================

    public List<WikiTeamMember> members(long teamId) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_team_member WHERE team_id = ? ORDER BY id ASC", teamId);
        List<WikiTeamMember> members = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            members.add(member(row));
        }
        return members;
    }

    public Optional<WikiTeamMember> findMember(long teamId, long userId) {
        return database.queryOne(
                        "SELECT * FROM wiki_team_member WHERE team_id = ? AND user_id = ?",
                        teamId, userId)
                .map(TeamStore::member);
    }

    public long addMember(long teamId, long userId, String userName, String role, String status) {
        return database.insert("INSERT INTO wiki_team_member"
                        + "(team_id, user_id, user_name, role, status, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                teamId, userId, userName, role, status, LocalDateTime.now());
    }

    public void updateMemberStatus(long teamId, long userId, String status) {
        database.update("UPDATE wiki_team_member SET status = ? WHERE team_id = ? AND user_id = ?",
                status, teamId, userId);
    }

    public void removeMember(long teamId, long userId) {
        database.update("DELETE FROM wiki_team_member WHERE team_id = ? AND user_id = ?",
                teamId, userId);
    }

    /** 已加入的人数（待批准的不算） */
    public int countJoined(long teamId) {
        return database.queryOne(
                        "SELECT COUNT(*) AS c FROM wiki_team_member WHERE team_id = ? AND status = ?",
                        teamId, MEMBER_JOINED)
                .map(row -> row.intValue("c", 0))
                .orElse(0);
    }

    // ==================== 举报 ====================

    public long addReport(long teamId, long reporterId, String reporterName, String reason) {
        return database.insert("INSERT INTO wiki_team_report"
                        + "(team_id, reporter_id, reporter_name, reason, handled, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                teamId, reporterId, reporterName, reason, Boolean.FALSE, LocalDateTime.now());
    }

    public List<WikiTeamReport> reports(long teamId) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_team_report WHERE team_id = ? ORDER BY id DESC", teamId);
        List<WikiTeamReport> list = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            WikiTeamReport report = new WikiTeamReport();
            report.setId(row.longOrNull("id"));
            report.setTeamId(row.longOrNull("team_id"));
            report.setReporterId(row.longOrNull("reporter_id"));
            report.setReporterName(row.string("reporter_name"));
            report.setReason(row.string("reason"));
            report.setHandled(row.boolValue("handled", false));
            report.setCreateAt(row.dateTime("create_at"));
            list.add(report);
        }
        return list;
    }

    // ==================== 映射 ====================

    private static WikiTeam team(DbRow row) {
        WikiTeam team = new WikiTeam();
        team.setId(row.longOrNull("id"));
        team.setEventYear(row.intOrNull("event_year"));
        team.setEventId(row.intOrNull("event_id"));
        team.setEventName(row.string("event_name"));
        team.setType(row.string("type"));
        team.setTitle(row.string("title"));
        team.setNote(row.string("note"));
        team.setCapacity(row.intOrNull("capacity"));
        team.setContact(row.string("contact"));
        team.setJoinMode(row.string("join_mode"));
        team.setOwnerId(row.longOrNull("owner_id"));
        team.setOwnerName(row.string("owner_name"));
        team.setStatus(row.string("status"));
        team.setDeletedBy(row.longOrNull("deleted_by"));
        team.setDeletedAt(row.dateTime("deleted_at"));
        team.setCreateAt(row.dateTime("create_at"));
        team.setUpdateAt(row.dateTime("update_at"));
        return team;
    }

    private static WikiTeamMember member(DbRow row) {
        WikiTeamMember member = new WikiTeamMember();
        member.setId(row.longOrNull("id"));
        member.setTeamId(row.longOrNull("team_id"));
        member.setUserId(row.longOrNull("user_id"));
        member.setUserName(row.string("user_name"));
        member.setRole(row.string("role"));
        member.setStatus(row.string("status"));
        member.setCreateAt(row.dateTime("create_at"));
        return member;
    }
}
