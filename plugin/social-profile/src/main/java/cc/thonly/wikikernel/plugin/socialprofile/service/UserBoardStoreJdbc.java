package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link UserBoardStore} 的插件实现：{@code wiki_user_board} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列
 * （SQLite / MySQL / H2 一套规则），数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 只做数据访问：谁能留言 / 谁能删等规则在 {@link BoardService}。
 */
@Service
public class UserBoardStoreJdbc implements UserBoardStore {

    private final PluginDatabase database;

    public UserBoardStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiUserBoard insert(WikiUserBoard message) {
        long id = database.insert("INSERT INTO wiki_user_board"
                        + "(to_user_id, from_user_id, content, parent_id, deleted, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                message.getToUserId(), message.getFromUserId(), text(message.getContent()),
                message.getParentId(), flag(message.getDeleted()), message.getCreateAt());
        message.setId(id);
        return message;
    }

    @Override
    public Optional<WikiUserBoard> find(long messageId) {
        return database.queryOne("SELECT * FROM wiki_user_board WHERE id = ?", messageId)
                .map(UserBoardStoreJdbc::board);
    }

    @Override
    public void update(WikiUserBoard message) {
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "to_user_id", message.getToUserId());
        add(assignments, args, "from_user_id", message.getFromUserId());
        add(assignments, args, "content", message.getContent());
        add(assignments, args, "parent_id", message.getParentId());
        add(assignments, args, "deleted", message.getDeleted());
        add(assignments, args, "create_at", message.getCreateAt());
        if (!assignments.isEmpty()) {
            args.add(message.getId());
            database.update("UPDATE wiki_user_board SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
    }

    @Override
    public void delete(long messageId) {
        database.update("DELETE FROM wiki_user_board WHERE id = ?", messageId);
    }

    @Override
    public List<WikiUserBoard> received(long toUserId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_board WHERE to_user_id = ?"
                + " ORDER BY id DESC" + limitClause(limit), toUserId));
    }

    @Override
    public long count(long toUserId) {
        return database.queryOne("SELECT COUNT(*) AS total FROM wiki_user_board WHERE to_user_id = ?", toUserId)
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    @Override
    public List<WikiUserBoard> bySender(long fromUserId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_board WHERE from_user_id = ?"
                + " ORDER BY id DESC" + limitClause(limit), fromUserId));
    }

    @Override
    public List<WikiUserBoard> replies(long parentId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_board WHERE parent_id = ?"
                + " ORDER BY id ASC" + limitClause(limit), parentId));
    }

    @Override
    public List<WikiUserBoard> recent(int limit) {
        return rows(database.query("SELECT * FROM wiki_user_board ORDER BY id DESC" + limitClause(limit)));
    }

    // ==================== 行 → 实体 ====================

    private static WikiUserBoard board(DbRow row) {
        WikiUserBoard board = new WikiUserBoard();
        board.setId(row.longOrNull("id"));
        board.setToUserId(row.longOrNull("to_user_id"));
        board.setFromUserId(row.longOrNull("from_user_id"));
        board.setContent(row.string("content"));
        board.setParentId(row.longOrNull("parent_id"));
        board.setDeleted(row.boolValue("deleted", false));
        board.setCreateAt(row.dateTime("create_at"));
        return board;
    }

    private static List<WikiUserBoard> rows(List<DbRow> rows) {
        List<WikiUserBoard> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(board(row));
        }
        return result;
    }

    // ==================== 小工具 ====================

    /** {@code LIMIT n}（{@code limit <= 0} 时不限制；n 是内部 int，不来自用户输入）。 */
    private static String limitClause(int limit) {
        return limit > 0 ? " LIMIT " + limit : "";
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

    /** 布尔入库：直接把 {@code Boolean} 交给驱动（H2 的 BOOLEAN 列不接受 0/1）。 */
    private static Boolean flag(Boolean value) {
        return value != null && value;
    }
}
