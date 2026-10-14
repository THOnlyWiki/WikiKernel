package cc.thonly.wikikernel.plugin.comments.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.comments.data.WikiComment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link CommentStore} 的插件实现：表 {@code wiki_comment} **属于插件自己**——
 * 表结构由 {@code CommentsPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列
 * （SQLite / MySQL / H2 一套规则），数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 行为与迁移前 core 的 {@code CommentStoreImpl}（MyBatis-Plus）一致：
 * 列表按 id 升序（发表时间顺序）、计数不含软删除、更新只写非 null 字段（{@code updateById} 语义）。
 * <p>
 * 只做数据访问：谁能发表 / 删除、写日志与文案都在服务层 / 页面层。
 */
@Service
public class CommentStoreJdbc implements CommentStore {

    private final PluginDatabase database;

    public CommentStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public List<WikiComment> list(String pageTitle) {
        String title = Strings.trimToEmpty(pageTitle);
        if (title.isEmpty()) {
            return List.of();
        }
        List<DbRow> rows = database.query(
                "SELECT * FROM wiki_comment WHERE page_title = ? ORDER BY id ASC", title);
        List<WikiComment> comments = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            comments.add(comment(row));
        }
        return comments;
    }

    @Override
    public Optional<WikiComment> find(long commentId) {
        return database.queryOne("SELECT * FROM wiki_comment WHERE id = ?", commentId)
                .map(CommentStoreJdbc::comment);
    }

    @Override
    public WikiComment insert(WikiComment comment) {
        long id = database.insert("INSERT INTO wiki_comment"
                        + "(page_title, parent_id, user_id, content, deleted, deleted_by, deleted_at, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                text(comment.getPageTitle()), comment.getParentId(), comment.getUserId(),
                text(comment.getContent()), flag(comment.getDeleted()), comment.getDeletedBy(),
                comment.getDeletedAt(), comment.getCreateAt());
        comment.setId(id);
        return comment;
    }

    @Override
    public void update(WikiComment comment) {
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "page_title", comment.getPageTitle());
        add(assignments, args, "parent_id", comment.getParentId());
        add(assignments, args, "user_id", comment.getUserId());
        add(assignments, args, "content", comment.getContent());
        add(assignments, args, "deleted", comment.getDeleted());
        add(assignments, args, "deleted_by", comment.getDeletedBy());
        add(assignments, args, "deleted_at", comment.getDeletedAt());
        add(assignments, args, "create_at", comment.getCreateAt());
        if (!assignments.isEmpty()) {
            args.add(comment.getId());
            database.update("UPDATE wiki_comment SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
    }

    @Override
    public long count(String pageTitle) {
        String title = Strings.trimToEmpty(pageTitle);
        if (title.isEmpty()) {
            return 0;
        }
        return database.queryOne(
                        "SELECT COUNT(*) AS total FROM wiki_comment WHERE page_title = ? AND deleted = ?",
                        title, false)
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    // ==================== 行 → 实体 ====================

    private static WikiComment comment(DbRow row) {
        WikiComment comment = new WikiComment();
        comment.setId(row.longOrNull("id"));
        comment.setPageTitle(row.string("page_title"));
        comment.setParentId(row.longOrNull("parent_id"));
        comment.setUserId(row.longOrNull("user_id"));
        comment.setContent(row.string("content"));
        comment.setDeleted(row.boolValue("deleted", false));
        comment.setDeletedBy(row.longOrNull("deleted_by"));
        comment.setDeletedAt(row.dateTime("deleted_at"));
        comment.setCreateAt(row.dateTime("create_at"));
        return comment;
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

    /**
     * 布尔入库：直接把 {@code Boolean} 交给驱动（H2 的 BOOLEAN 列不接受 0/1；
     * SQLite / MySQL 的驱动会把 true/false 写成 1/0），读取侧由 {@link DbRow} 归一化。
     */
    private static Boolean flag(Boolean value) {
        return value != null && value;
    }
}
