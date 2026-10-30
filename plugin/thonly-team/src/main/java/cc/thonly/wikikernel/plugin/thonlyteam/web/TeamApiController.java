package cc.thonly.wikikernel.plugin.thonlyteam.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.thonlyteam.TeamPlugin;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeam;
import cc.thonly.wikikernel.plugin.thonlyteam.data.WikiTeamMember;
import cc.thonly.wikikernel.plugin.thonlyteam.service.TeamStore;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组队跑展 API。
 *
 * <p>权限：所有写操作都要求「已登录 + 未被封禁 + 对该页面有编辑权限」——
 * 权限挂在「线下活动速查表」上，和活动数据共用一套，站长可以在
 * 特殊:权限配置 里按用户组收紧（例如只留给自动确认用户）。
 *
 * <p>联系方式只在**已加入的成员和团长**眼里出现，列表接口永远不返回。
 *
 * <p>解散：团长可以关自己的队；站长（对该页面有 delete 权限）可以解散任何队伍，
 * 解散是软删除，数据留着可追溯。
 */
@RestController
@RequestMapping("/api/thonly-team")
public class TeamApiController {

    /** 权限判断挂靠的页面，与活动速查表保持一致 */
    private static final String ACL_PAGE = "线下活动速查表";

    private static final int TITLE_MAX = 60;
    private static final int NOTE_MAX = 500;
    private static final int CONTACT_MAX = 120;
    private static final int REASON_MAX = 200;
    private static final int MAX_CAPACITY = 30;

    private final TeamStore store;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final LogWriter logWriter;
    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public TeamApiController(TeamStore store, AccessCheck access, CurrentUser currentUser,
                             LogWriter logWriter, SiteConfig siteConfig,
                             MessageSource messageSource) {
        this.store = store;
        this.access = access;
        this.currentUser = currentUser;
        this.logWriter = logWriter;
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    // ==================== 读 ====================

    /** 某场活动下的队伍列表；eventId 省略时列整年。不需要登录，也不返回联系方式。 */
    @GetMapping("/list")
    public AjaxResult<Map<String, Object>> list(
            @RequestParam("year") int year,
            @RequestParam(value = "eventId", required = false, defaultValue = "0") int eventId) {
        if (!enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.featureDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<WikiTeam> teams = eventId > 0
                ? store.listByEvent(year, eventId) : store.listByYear(year);
        for (WikiTeam team : teams) {
            rows.add(summary(team, viewer));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teams", rows);
        data.put("loggedIn", viewer.loggedIn());
        return AjaxResult.success(data);
    }

    /** 队伍详情：成员列表 + （已加入时才给的）联系方式 */
    @GetMapping("/detail")
    public AjaxResult<Map<String, Object>> detail(@RequestParam("id") long id) {
        if (!enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.featureDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty() || TeamStore.STATUS_DELETED.equals(found.get().getStatus())) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        WikiTeam team = found.get();
        boolean isOwner = viewer.loggedIn() && team.getOwnerId() != null
                && team.getOwnerId() == viewer.id();
        Optional<WikiTeamMember> mine = viewer.loggedIn()
                ? store.findMember(id, viewer.id()) : Optional.empty();
        boolean joined = mine.isPresent()
                && TeamStore.MEMBER_JOINED.equals(mine.get().getStatus());

        List<Map<String, Object>> members = new ArrayList<>();
        for (WikiTeamMember member : store.members(id)) {
            // 待批准的人只有团长看得见
            if (TeamStore.MEMBER_PENDING.equals(member.getStatus()) && !isOwner) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", member.getUserId());
            row.put("userName", member.getUserName());
            row.put("role", member.getRole());
            row.put("status", member.getStatus());
            members.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", summary(team, viewer));
        data.put("members", members);
        data.put("isOwner", isOwner);
        data.put("joined", joined);
        data.put("pending", mine.isPresent()
                && TeamStore.MEMBER_PENDING.equals(mine.get().getStatus()));
        // 联系方式：只有团长和已加入的成员拿得到
        data.put("contact", (isOwner || joined) ? Strings.trimToEmpty(team.getContact()) : "");
        data.put("contactVisible", isOwner || joined);
        data.put("canManage", isOwner || canModerate(viewer));
        return AjaxResult.success(data);
    }

    // ==================== 写 ====================

    @PostMapping("/create")
    public AjaxResult<Map<String, Object>> create(
            @RequestParam("eventYear") int eventYear,
            @RequestParam("eventId") int eventId,
            @RequestParam(value = "eventName", required = false) String eventName,
            @RequestParam("type") String type,
            @RequestParam("title") String title,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "joinMode", required = false, defaultValue = "approve") String joinMode,
            @RequestParam(value = "capacity", required = false, defaultValue = "2") int capacity) {
        AjaxResult<Map<String, Object>> denied = checkWrite();
        if (denied != null) {
            return denied;
        }
        String cleanTitle = Strings.trimToEmpty(title);
        if (cleanTitle.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.titleRequired"));
        }
        if (cleanTitle.length() > TITLE_MAX) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.titleTooLong", TITLE_MAX));
        }
        String cleanNote = Strings.trimToEmpty(note);
        if (cleanNote.length() > NOTE_MAX) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.noteTooLong", NOTE_MAX));
        }
        String cleanContact = Strings.trimToEmpty(contact);
        if (cleanContact.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.contactRequired"));
        }
        if (cleanContact.length() > CONTACT_MAX) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.contactTooLong", CONTACT_MAX));
        }

        Viewer viewer = currentUser.viewer();
        WikiTeam team = new WikiTeam();
        team.setEventYear(eventYear);
        team.setEventId(eventId);
        team.setEventName(Strings.trimToEmpty(eventName));
        team.setType(Strings.trimToEmpty(type).isEmpty() ? "company" : Strings.trimToEmpty(type));
        team.setTitle(cleanTitle);
        team.setNote(cleanNote);
        team.setContact(cleanContact);
        team.setJoinMode("instant".equals(joinMode) ? "instant" : "approve");
        team.setCapacity(Math.max(2, Math.min(MAX_CAPACITY, capacity)));
        team.setOwnerId(viewer.id());
        team.setOwnerName(viewer.name());
        team.setStatus(TeamStore.STATUS_OPEN);

        long id = store.insert(team);
        store.addMember(id, viewer.id(), viewer.name(), "owner", TeamStore.MEMBER_JOINED);
        logWriter.record(TeamPlugin.LOG_TEAM_CREATE, viewer.id(),
                "活动 " + eventYear + "/" + eventId + " · " + cleanTitle, cleanTitle);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return AjaxResult.success(message("team.created"), data);
    }

    /** 加入：立即进入 or 等团长批准 */
    @PostMapping("/join")
    public AjaxResult<Map<String, Object>> join(@RequestParam("id") long id) {
        AjaxResult<Map<String, Object>> denied = checkWrite();
        if (denied != null) {
            return denied;
        }
        Viewer viewer = currentUser.viewer();
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty() || TeamStore.STATUS_DELETED.equals(found.get().getStatus())) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        WikiTeam team = found.get();
        if (!TeamStore.STATUS_OPEN.equals(team.getStatus())) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.closed"));
        }
        if (team.getOwnerId() != null && team.getOwnerId() == viewer.id()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.alreadyInTeam"));
        }
        Optional<WikiTeamMember> mine = store.findMember(id, viewer.id());
        if (mine.isPresent() && !TeamStore.MEMBER_REJECTED.equals(mine.get().getStatus())) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.alreadyInTeam"));
        }
        int joined = store.countJoined(id);
        if (joined >= team.getCapacity()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.full"));
        }

        boolean instant = "instant".equals(team.getJoinMode());
        String status = instant ? TeamStore.MEMBER_JOINED : TeamStore.MEMBER_PENDING;
        if (mine.isPresent()) {
            store.updateMemberStatus(id, viewer.id(), status);
        } else {
            store.addMember(id, viewer.id(), viewer.name(), "member", status);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        return AjaxResult.success(
                message(instant ? "team.joined" : "team.pending"), data);
    }

    @PostMapping("/leave")
    public AjaxResult<Map<String, Object>> leave(@RequestParam("id") long id) {
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        if (found.get().getOwnerId() != null && found.get().getOwnerId() == viewer.id()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.ownerCannotLeave"));
        }
        store.removeMember(id, viewer.id());
        return AjaxResult.success(message("team.left"), null);
    }

    /** 团长批准 / 拒绝 */
    @PostMapping("/review")
    public AjaxResult<Map<String, Object>> review(@RequestParam("id") long id,
                                                  @RequestParam("userId") long userId,
                                                  @RequestParam("approve") boolean approve) {
        Viewer viewer = currentUser.viewer();
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        if (!viewer.loggedIn() || found.get().getOwnerId() == null
                || found.get().getOwnerId() != viewer.id()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.notOwner"));
        }
        if (approve && store.countJoined(id) >= found.get().getCapacity()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.full"));
        }
        store.updateMemberStatus(id, userId,
                approve ? TeamStore.MEMBER_JOINED : TeamStore.MEMBER_REJECTED);
        return AjaxResult.success(
                message(approve ? "team.approved" : "team.rejected"), null);
    }

    /** 团长关闭自己的队伍（不再招募） */
    @PostMapping("/close")
    public AjaxResult<Map<String, Object>> close(@RequestParam("id") long id) {
        Viewer viewer = currentUser.viewer();
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        if (!viewer.loggedIn() || found.get().getOwnerId() == null
                || found.get().getOwnerId() != viewer.id()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.notOwner"));
        }
        store.updateStatus(id, TeamStore.STATUS_CLOSED, viewer.id());
        return AjaxResult.success(message("team.closedByOwner"), null);
    }

    /**
     * 解散：团长关自己的队，或站长（对该页面有 delete 权限）解散任何队伍。
     * 软删除，数据留着。
     */
    @PostMapping("/delete")
    public AjaxResult<Map<String, Object>> delete(@RequestParam("id") long id) {
        Viewer viewer = currentUser.viewer();
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        boolean isOwner = viewer.loggedIn() && found.get().getOwnerId() != null
                && found.get().getOwnerId() == viewer.id();
        if (!isOwner && !canModerate(viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.notOwner"));
        }
        store.updateStatus(id, TeamStore.STATUS_DELETED, viewer.id());
        logWriter.record(TeamPlugin.LOG_TEAM_DELETE, viewer.loggedIn() ? viewer.id() : null,
                "队伍 " + id + " · " + found.get().getTitle(),
                isOwner ? "团长解散" : "站长解散");
        return AjaxResult.success(message("team.deleted"), null);
    }

    /** 举报：只记录 + 写日志，不自动处置 */
    @PostMapping("/report")
    public AjaxResult<Map<String, Object>> report(@RequestParam("id") long id,
                                                  @RequestParam("reason") String reason) {
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<WikiTeam> found = store.find(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("team.notFound"));
        }
        String cleanReason = Strings.trimToEmpty(reason);
        if (cleanReason.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("team.reportRequired"));
        }
        if (cleanReason.length() > REASON_MAX) {
            cleanReason = cleanReason.substring(0, REASON_MAX);
        }
        store.addReport(id, viewer.id(), viewer.name(), cleanReason);
        logWriter.record(TeamPlugin.LOG_TEAM_REPORT, viewer.id(),
                "队伍 " + id + " · " + found.get().getTitle(), cleanReason);
        return AjaxResult.success(message("team.reported"), null);
    }

    // ==================== 内部 ====================

    /** 写操作的三道门槛：功能开着、已登录、未被封禁、有编辑权限 */
    private <T> AjaxResult<T> checkWrite() {
        if (!enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.featureDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<String> block = access.blockNotice(viewer);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, block.get());
        }
        if (!access.can(AccessAction.EDIT, ACL_PAGE, viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("team.editForbidden"));
        }
        return null;
    }

    private boolean canModerate(Viewer viewer) {
        return access.can(AccessAction.DELETE, ACL_PAGE, viewer);
    }

    private boolean enabled() {
        return siteConfig.getBoolean(TeamPlugin.FEATURE_KEY, true);
    }

    private Map<String, Object> summary(WikiTeam team, Viewer viewer) {
        int joined = store.countJoined(team.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", team.getId());
        row.put("eventYear", team.getEventYear());
        row.put("eventId", team.getEventId());
        row.put("eventName", team.getEventName());
        row.put("type", team.getType());
        row.put("title", team.getTitle());
        row.put("note", team.getNote());
        row.put("capacity", team.getCapacity());
        row.put("joined", joined);
        row.put("remaining", Math.max(0, team.getCapacity() - joined));
        row.put("joinMode", team.getJoinMode());
        row.put("ownerId", team.getOwnerId());
        row.put("ownerName", team.getOwnerName());
        row.put("status", team.getStatus());
        row.put("mine", viewer.loggedIn() && team.getOwnerId() != null
                && team.getOwnerId() == viewer.id());
        row.put("createAt", team.getCreateAt() == null ? "" : team.getCreateAt().toString());
        return row;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
