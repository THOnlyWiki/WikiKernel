package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.RecentChangesStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link AbuseFilterStore} 的插件实现：三张表（{@code wiki_abuse_filter} /
 * {@code wiki_abuse_filter_revision} / {@code wiki_abuse_log}）**属于插件自己**——
 * 表结构由 {@code AbuseFilterPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列
 * （SQLite / MySQL / H2 一套规则），数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 「测试过滤器」需要的只读数据（最近编辑 / 编辑者概况）来自通用的
 * {@link RecentChangesStore}（core 的 revision / page / user 表），插件不直接摸 core 表结构。
 * <p>
 * 只做数据访问：条件求值、操作执行、日志文案、权限判断都在服务层 / 页面层。
 */
@Service
public class AbuseFilterStoreJdbc implements AbuseFilterStore {

    private final PluginDatabase database;
    private final RecentChangesStore recentChanges;

    public AbuseFilterStoreJdbc(PluginDatabase database, RecentChangesStore recentChanges) {
        this.database = database;
        this.recentChanges = recentChanges;
    }

    // ==================== 过滤器（wiki_abuse_filter） ====================

    @Override
    public List<WikiAbuseFilter> filters(boolean includeHidden) {
        List<DbRow> rows = includeHidden
                ? database.query("SELECT * FROM wiki_abuse_filter ORDER BY id DESC")
                : database.query("SELECT * FROM wiki_abuse_filter WHERE hidden = ? ORDER BY id DESC", false);
        List<WikiAbuseFilter> filters = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            filters.add(filter(row));
        }
        return filters;
    }

    @Override
    public Optional<WikiAbuseFilter> find(long filterId) {
        return database.queryOne("SELECT * FROM wiki_abuse_filter WHERE id = ?", filterId)
                .map(AbuseFilterStoreJdbc::filter);
    }

    @Override
    public long save(WikiAbuseFilter filter) {
        if (filter.getId() == null) {
            long id = database.insert("INSERT INTO wiki_abuse_filter"
                            + "(description, pattern, actions, tags, throttle_count, throttle_period,"
                            + " throttle_group, enabled, deleted, hidden, notes, warn_message, warn_page,"
                            + " disallow_message, disallow_page, block_talk, block_anon_days, block_user_days,"
                            + " hits, last_editor_id, last_edit_at, create_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    text(filter.getDescription()), text(filter.getPattern()), text(filter.getActions()),
                    text(filter.getTags()), number(filter.getThrottleCount()), number(filter.getThrottlePeriod()),
                    text(filter.getThrottleGroup()), flag(filter.getEnabled()), flag(filter.getDeleted()),
                    flag(filter.getHidden()), text(filter.getNotes()), text(filter.getWarnMessage()),
                    text(filter.getWarnPage()), text(filter.getDisallowMessage()), text(filter.getDisallowPage()),
                    flag(filter.getBlockTalk()), number(filter.getBlockAnonDays()), number(filter.getBlockUserDays()),
                    number(filter.getHits()), filter.getLastEditorId(), filter.getLastEditAt(), filter.getCreateAt());
            filter.setId(id);
            return id;
        }
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "description", filter.getDescription());
        add(assignments, args, "pattern", filter.getPattern());
        add(assignments, args, "actions", filter.getActions());
        add(assignments, args, "tags", filter.getTags());
        add(assignments, args, "throttle_count", filter.getThrottleCount());
        add(assignments, args, "throttle_period", filter.getThrottlePeriod());
        add(assignments, args, "throttle_group", filter.getThrottleGroup());
        add(assignments, args, "enabled", filter.getEnabled());
        add(assignments, args, "deleted", filter.getDeleted());
        add(assignments, args, "hidden", filter.getHidden());
        add(assignments, args, "notes", filter.getNotes());
        add(assignments, args, "warn_message", filter.getWarnMessage());
        add(assignments, args, "warn_page", filter.getWarnPage());
        add(assignments, args, "disallow_message", filter.getDisallowMessage());
        add(assignments, args, "disallow_page", filter.getDisallowPage());
        add(assignments, args, "block_talk", filter.getBlockTalk());
        add(assignments, args, "block_anon_days", filter.getBlockAnonDays());
        add(assignments, args, "block_user_days", filter.getBlockUserDays());
        add(assignments, args, "hits", filter.getHits());
        add(assignments, args, "last_editor_id", filter.getLastEditorId());
        add(assignments, args, "last_edit_at", filter.getLastEditAt());
        add(assignments, args, "create_at", filter.getCreateAt());
        if (!assignments.isEmpty()) {
            args.add(filter.getId());
            database.update("UPDATE wiki_abuse_filter SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
        return filter.getId();
    }

    @Override
    public boolean updateFlags(long filterId, Boolean enabled, Boolean deleted, Boolean hidden) {
        if (enabled == null && deleted == null && hidden == null) {
            return find(filterId).isPresent();
        }
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "enabled", enabled);
        add(assignments, args, "deleted", deleted);
        add(assignments, args, "hidden", hidden);
        args.add(filterId);
        database.update("UPDATE wiki_abuse_filter SET " + String.join(", ", assignments) + " WHERE id = ?",
                args.toArray());
        return find(filterId).isPresent();
    }

    @Override
    public void bumpHits(long filterId) {
        database.update("UPDATE wiki_abuse_filter SET hits = hits + 1 WHERE id = ?", filterId);
    }

    @Override
    public boolean delete(long filterId) {
        if (find(filterId).isEmpty()) {
            return false;
        }
        database.transaction(() -> {
            database.update("DELETE FROM wiki_abuse_filter_revision WHERE filter_id = ?", filterId);
            database.update("DELETE FROM wiki_abuse_filter WHERE id = ?", filterId);
            return true;
        });
        return true;
    }

    // ==================== 修订历史（wiki_abuse_filter_revision） ====================

    @Override
    public void insertRevision(WikiAbuseFilterRevision revision) {
        long id = database.insert("INSERT INTO wiki_abuse_filter_revision"
                        + "(filter_id, description, pattern, actions, tags, throttle_count, throttle_period,"
                        + " throttle_group, enabled, deleted, hidden, notes, warn_message, warn_page,"
                        + " disallow_message, disallow_page, block_talk, block_anon_days, block_user_days,"
                        + " user_id, comment, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                revision.getFilterId(), text(revision.getDescription()), text(revision.getPattern()),
                text(revision.getActions()), text(revision.getTags()), number(revision.getThrottleCount()),
                number(revision.getThrottlePeriod()), text(revision.getThrottleGroup()),
                flag(revision.getEnabled()), flag(revision.getDeleted()), flag(revision.getHidden()),
                text(revision.getNotes()), text(revision.getWarnMessage()), text(revision.getWarnPage()),
                text(revision.getDisallowMessage()), text(revision.getDisallowPage()),
                flag(revision.getBlockTalk()), number(revision.getBlockAnonDays()),
                number(revision.getBlockUserDays()), revision.getUserId(), text(revision.getComment()),
                revision.getCreateAt());
        revision.setId(id);
    }

    @Override
    public List<WikiAbuseFilterRevision> history(long filterId) {
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_abuse_filter_revision WHERE filter_id = ? ORDER BY id DESC", filterId);
        List<WikiAbuseFilterRevision> history = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            history.add(revision(row));
        }
        return history;
    }

    @Override
    public Optional<WikiAbuseFilterRevision> findRevision(long revisionId) {
        return database.queryOne("SELECT * FROM wiki_abuse_filter_revision WHERE id = ?", revisionId)
                .map(AbuseFilterStoreJdbc::revision);
    }

    // ==================== 滥用日志（wiki_abuse_log） ====================

    @Override
    public void insertLog(WikiAbuseLog entry) {
        long id = database.insert("INSERT INTO wiki_abuse_log"
                        + "(filter_id, user_id, ip, page_title, action, actions_taken, variables, hided, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.getFilterId(), entry.getUserId(), text(entry.getIp()), text(entry.getPageTitle()),
                text(entry.getAction()), text(entry.getActionsTaken()), text(entry.getVariables()),
                flag(entry.getHided()), entry.getCreateAt());
        entry.setId(id);
    }

    @Override
    public List<WikiAbuseLog> logs(boolean includeHided, int limit) {
        int effective = Math.min(Math.max(1, limit), 500);
        List<DbRow> rows = includeHided
                ? database.query("SELECT * FROM wiki_abuse_log ORDER BY id DESC LIMIT ?", effective)
                : database.query("SELECT * FROM wiki_abuse_log WHERE hided = ? ORDER BY id DESC LIMIT ?",
                false, effective);
        List<WikiAbuseLog> logs = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            logs.add(log(row));
        }
        return logs;
    }

    @Override
    public Optional<WikiAbuseLog> findLog(long logId) {
        return database.queryOne("SELECT * FROM wiki_abuse_log WHERE id = ?", logId)
                .map(AbuseFilterStoreJdbc::log);
    }

    @Override
    public boolean hideLog(long logId, boolean hided) {
        if (findLog(logId).isEmpty()) {
            return false;
        }
        database.update("UPDATE wiki_abuse_log SET hided = ? WHERE id = ?", hided, logId);
        return true;
    }

    // ==================== 测试过滤器需要的只读查询 ====================

    @Override
    public List<RecentEdit> recentEdits(int limit) {
        List<RecentEdit> edits = new ArrayList<>();
        for (RecentChangesStore.RecentEdit edit : recentChanges.recentEdits(limit)) {
            edits.add(new RecentEdit(edit.revisionId(), edit.pageId(), edit.pageTitle(), edit.userId(),
                    edit.userName(), edit.time(), edit.summary(), edit.content(), edit.oldContent()));
        }
        return edits;
    }

    @Override
    public Optional<UserStats> userStats(long userId) {
        return recentChanges.editorStats(userId)
                .map(stats -> new UserStats(stats.editCount(), stats.registeredAt()));
    }

    // ==================== 行 → 实体 ====================

    private static WikiAbuseFilter filter(DbRow row) {
        WikiAbuseFilter filter = new WikiAbuseFilter();
        filter.setId(row.longOrNull("id"));
        filter.setDescription(row.string("description"));
        filter.setPattern(row.string("pattern"));
        filter.setActions(row.string("actions"));
        filter.setTags(row.string("tags"));
        filter.setThrottleCount(row.intValue("throttle_count", 0));
        filter.setThrottlePeriod(row.intValue("throttle_period", 0));
        filter.setThrottleGroup(row.string("throttle_group"));
        filter.setEnabled(row.boolValue("enabled", false));
        filter.setDeleted(row.boolValue("deleted", false));
        filter.setHidden(row.boolValue("hidden", false));
        filter.setNotes(row.string("notes"));
        filter.setWarnMessage(row.string("warn_message"));
        filter.setWarnPage(row.string("warn_page"));
        filter.setDisallowMessage(row.string("disallow_message"));
        filter.setDisallowPage(row.string("disallow_page"));
        filter.setBlockTalk(row.boolValue("block_talk", false));
        filter.setBlockAnonDays(row.intValue("block_anon_days", 3));
        filter.setBlockUserDays(row.intValue("block_user_days", 3));
        filter.setHits(row.intValue("hits", 0));
        filter.setLastEditorId(row.longOrNull("last_editor_id"));
        filter.setLastEditAt(row.dateTime("last_edit_at"));
        filter.setCreateAt(row.dateTime("create_at"));
        return filter;
    }

    private static WikiAbuseFilterRevision revision(DbRow row) {
        WikiAbuseFilterRevision revision = new WikiAbuseFilterRevision();
        revision.setId(row.longOrNull("id"));
        revision.setFilterId(row.longOrNull("filter_id"));
        revision.setDescription(row.string("description"));
        revision.setPattern(row.string("pattern"));
        revision.setActions(row.string("actions"));
        revision.setTags(row.string("tags"));
        revision.setThrottleCount(row.intValue("throttle_count", 0));
        revision.setThrottlePeriod(row.intValue("throttle_period", 0));
        revision.setThrottleGroup(row.string("throttle_group"));
        revision.setEnabled(row.boolValue("enabled", false));
        revision.setDeleted(row.boolValue("deleted", false));
        revision.setHidden(row.boolValue("hidden", false));
        revision.setNotes(row.string("notes"));
        revision.setWarnMessage(row.string("warn_message"));
        revision.setWarnPage(row.string("warn_page"));
        revision.setDisallowMessage(row.string("disallow_message"));
        revision.setDisallowPage(row.string("disallow_page"));
        revision.setBlockTalk(row.boolValue("block_talk", false));
        revision.setBlockAnonDays(row.intValue("block_anon_days", 3));
        revision.setBlockUserDays(row.intValue("block_user_days", 3));
        revision.setUserId(row.longOrNull("user_id"));
        revision.setComment(row.string("comment"));
        revision.setCreateAt(row.dateTime("create_at"));
        return revision;
    }

    private static WikiAbuseLog log(DbRow row) {
        WikiAbuseLog log = new WikiAbuseLog();
        log.setId(row.longOrNull("id"));
        log.setFilterId(row.longOrNull("filter_id"));
        log.setUserId(row.longOrNull("user_id"));
        log.setIp(row.string("ip"));
        log.setPageTitle(row.string("page_title"));
        log.setAction(row.string("action"));
        log.setActionsTaken(row.string("actions_taken"));
        log.setVariables(row.string("variables"));
        log.setHided(row.boolValue("hided", false));
        log.setCreateAt(row.dateTime("create_at"));
        return log;
    }

    // ==================== 小工具 ====================

    /** 动态 UPDATE 的赋值片段（{@code null} = 不改这一列；与 MyBatis-Plus 的 updateById 语义一致）。 */
    private static void add(List<String> assignments, List<Object> args, String column, Object value) {
        if (value == null) {
            return;
        }
        assignments.add(column + " = ?");
        args.add(value);
    }

    private static String text(String value) {
        return Strings.trimToEmpty(value);
    }

    private static Integer number(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 布尔入库：直接把 {@code Boolean} 交给驱动（H2 的 BOOLEAN 列不接受 0/1；
     * SQLite / MySQL 的驱动会把 true/false 写成 1/0），读取侧由 {@link DbRow} 归一化。
     */
    private static Boolean flag(Boolean value) {
        return value != null && value;
    }
}
