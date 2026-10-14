package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ModerationStore} 的插件实现：表 {@code wiki_pending_change} **属于插件自己**——
 * 表结构由 {@code ModerationPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列
 * （SQLite / MySQL / H2 一套规则），数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 查询语义与迁移前 core 的 {@code ModerationStoreImpl}（MyBatis-Plus）一致：
 * 队列按 id 倒序（新的在前）、{@code titleLike} 是包含匹配（{@code LIKE %…%}）、
 * {@code limit <= 0} 不限条数、更新只写非 null 字段（{@code updateById} 语义）。
 */
@Service
public class ModerationStoreJdbc implements ModerationStore {

    private final PluginDatabase database;

    public ModerationStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiPendingChange insert(WikiPendingChange change) {
        long id = database.insert("INSERT INTO wiki_pending_change"
                        + "(change_type, title, page_id, user_id, comment, content, minor, base_revision_id,"
                        + " ai_assisted, ai_model, file_name, file_description, file_stored_name, file_size,"
                        + " file_id, hard_delete, status, create_at, reviewer_id, reviewed_at, review_comment)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                text(change.getChangeType()), text(change.getTitle()), change.getPageId(), change.getUserId(),
                text(change.getComment()), change.getContent(), flag(change.getMinor()),
                change.getBaseRevisionId(), flag(change.getAiAssisted()), text(change.getAiModel()),
                change.getFileName(), change.getFileDescription(), change.getFileStoredName(),
                change.getFileSize(), change.getFileId(), flag(change.getHardDelete()),
                text(change.getStatus()), change.getCreateAt(), change.getReviewerId(),
                change.getReviewedAt(), change.getReviewComment());
        change.setId(id);
        return change;
    }

    @Override
    public void update(WikiPendingChange change) {
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "change_type", change.getChangeType());
        add(assignments, args, "title", change.getTitle());
        add(assignments, args, "page_id", change.getPageId());
        add(assignments, args, "user_id", change.getUserId());
        add(assignments, args, "comment", change.getComment());
        add(assignments, args, "content", change.getContent());
        add(assignments, args, "minor", change.getMinor());
        add(assignments, args, "base_revision_id", change.getBaseRevisionId());
        add(assignments, args, "ai_assisted", change.getAiAssisted());
        add(assignments, args, "ai_model", change.getAiModel());
        add(assignments, args, "file_name", change.getFileName());
        add(assignments, args, "file_description", change.getFileDescription());
        add(assignments, args, "file_stored_name", change.getFileStoredName());
        add(assignments, args, "file_size", change.getFileSize());
        add(assignments, args, "file_id", change.getFileId());
        add(assignments, args, "hard_delete", change.getHardDelete());
        add(assignments, args, "status", change.getStatus());
        add(assignments, args, "create_at", change.getCreateAt());
        add(assignments, args, "reviewer_id", change.getReviewerId());
        add(assignments, args, "reviewed_at", change.getReviewedAt());
        add(assignments, args, "review_comment", change.getReviewComment());
        if (!assignments.isEmpty()) {
            args.add(change.getId());
            database.update("UPDATE wiki_pending_change SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
    }

    @Override
    public Optional<WikiPendingChange> find(long id) {
        return database.queryOne("SELECT * FROM wiki_pending_change WHERE id = ?", id)
                .map(ModerationStoreJdbc::change);
    }

    @Override
    public List<WikiPendingChange> list(String status, String type, String titleLike, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wiki_pending_change");
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (!Strings.isBlank(status)) {
            conditions.add("status = ?");
            args.add(status.trim());
        }
        if (!Strings.isBlank(type)) {
            conditions.add("change_type = ?");
            args.add(type.trim());
        }
        if (!Strings.isBlank(titleLike)) {
            conditions.add("title LIKE ?");
            args.add("%" + titleLike.trim() + "%");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY id DESC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            args.add(limit);
        }
        return changes(database.query(sql.toString(), args.toArray()));
    }

    @Override
    public List<WikiPendingChange> forPage(String title) {
        if (Strings.isBlank(title)) {
            return List.of();
        }
        return changes(database.query(
                "SELECT * FROM wiki_pending_change WHERE status = ? AND title = ? ORDER BY id DESC",
                WikiPendingChange.STATUS_PENDING, title.trim()));
    }

    @Override
    public long count(String status) {
        if (Strings.isBlank(status)) {
            return scalar("SELECT COUNT(*) AS total FROM wiki_pending_change");
        }
        return scalar("SELECT COUNT(*) AS total FROM wiki_pending_change WHERE status = ?", status.trim());
    }

    @Override
    public long countForPage(String title, String status) {
        if (Strings.isBlank(title)) {
            return 0;
        }
        if (Strings.isBlank(status)) {
            return scalar("SELECT COUNT(*) AS total FROM wiki_pending_change WHERE title = ?", title.trim());
        }
        return scalar("SELECT COUNT(*) AS total FROM wiki_pending_change WHERE title = ? AND status = ?",
                title.trim(), status.trim());
    }

    @Override
    public List<WikiPendingChange> byUser(long userId, int limit) {
        if (limit > 0) {
            return changes(database.query(
                    "SELECT * FROM wiki_pending_change WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                    userId, limit));
        }
        return changes(database.query(
                "SELECT * FROM wiki_pending_change WHERE user_id = ? ORDER BY id DESC", userId));
    }

    // ==================== 行 → 实体 ====================

    private static List<WikiPendingChange> changes(List<DbRow> rows) {
        List<WikiPendingChange> changes = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            changes.add(change(row));
        }
        return changes;
    }

    private static WikiPendingChange change(DbRow row) {
        WikiPendingChange change = new WikiPendingChange();
        change.setId(row.longOrNull("id"));
        change.setChangeType(row.string("change_type"));
        change.setTitle(row.string("title"));
        change.setPageId(row.longOrNull("page_id"));
        change.setUserId(row.longOrNull("user_id"));
        change.setComment(row.string("comment"));
        change.setContent(row.textOrNull("content"));
        change.setMinor(row.boolValue("minor", false));
        change.setBaseRevisionId(row.longOrNull("base_revision_id"));
        change.setAiAssisted(row.boolValue("ai_assisted", false));
        change.setAiModel(row.string("ai_model"));
        change.setFileName(row.string("file_name"));
        change.setFileDescription(row.string("file_description"));
        change.setFileStoredName(row.string("file_stored_name"));
        change.setFileSize(row.longOrNull("file_size"));
        change.setFileId(row.longOrNull("file_id"));
        change.setHardDelete(row.boolValue("hard_delete", false));
        change.setStatus(row.string("status"));
        change.setCreateAt(row.dateTime("create_at"));
        change.setReviewerId(row.longOrNull("reviewer_id"));
        change.setReviewedAt(row.dateTime("reviewed_at"));
        change.setReviewComment(row.textOrNull("review_comment"));
        return change;
    }

    // ==================== 小工具 ====================

    /** 单值计数（重命名成 scalar：避免与接口的 count(String) 同名导致误解析/自递归）。 */
    private long scalar(String sql, Object... args) {
        return database.queryOne(sql, args).map(row -> row.longValue("total", 0L)).orElse(0L);
    }

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

    /**
     * 布尔入库：直接把 {@code Boolean} 交给驱动（H2 的 BOOLEAN 列不接受 0/1；
     * SQLite / MySQL 的驱动会把 true/false 写成 1/0），读取侧由 {@link DbRow} 归一化。
     */
    private static Boolean flag(Boolean value) {
        return value != null && value;
    }
}
