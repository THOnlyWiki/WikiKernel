package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link MessageStore} 的插件实现：{@code wiki_message} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 只做数据访问：谁给谁发、能不能删等规则在 {@link MessageCenter}。
 */
@Service
public class MessageStoreJdbc implements MessageStore {

    private final PluginDatabase database;

    public MessageStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiMessage insert(WikiMessage message) {
        long id = database.insert("INSERT INTO wiki_message"
                        + "(from_user_id, to_user_id, content, read, read_at, from_deleted, to_deleted, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                message.getFromUserId(), message.getToUserId(), text(message.getContent()),
                flag(message.getRead()), message.getReadAt(), flag(message.getFromDeleted()),
                flag(message.getToDeleted()), message.getCreateAt());
        message.setId(id);
        return message;
    }

    @Override
    public Optional<WikiMessage> find(long messageId) {
        return database.queryOne("SELECT * FROM wiki_message WHERE id = ?", messageId)
                .map(MessageStoreJdbc::message);
    }

    @Override
    public void update(WikiMessage message) {
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "from_user_id", message.getFromUserId());
        add(assignments, args, "to_user_id", message.getToUserId());
        add(assignments, args, "content", message.getContent());
        add(assignments, args, "read", message.getRead());
        add(assignments, args, "read_at", message.getReadAt());
        add(assignments, args, "from_deleted", message.getFromDeleted());
        add(assignments, args, "to_deleted", message.getToDeleted());
        add(assignments, args, "create_at", message.getCreateAt());
        if (!assignments.isEmpty()) {
            args.add(message.getId());
            database.update("UPDATE wiki_message SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
    }

    @Override
    public void delete(long messageId) {
        database.update("DELETE FROM wiki_message WHERE id = ?", messageId);
    }

    @Override
    public List<WikiMessage> received(long userId, int limit) {
        return rows(database.query("SELECT * FROM wiki_message"
                + " WHERE to_user_id = ? AND to_deleted = ?"
                + " ORDER BY id DESC" + limitClause(limit), userId, false));
    }

    @Override
    public List<WikiMessage> sent(long userId, int limit) {
        return rows(database.query("SELECT * FROM wiki_message"
                + " WHERE from_user_id = ? AND from_deleted = ?"
                + " ORDER BY id DESC" + limitClause(limit), userId, false));
    }

    @Override
    public List<WikiMessage> all(long userId, int limit) {
        return rows(database.query("SELECT * FROM wiki_message"
                + " WHERE (from_user_id = ? AND from_deleted = ?)"
                + " OR (to_user_id = ? AND to_deleted = ?)"
                + " ORDER BY id ASC" + limitClause(limit), userId, false, userId, false));
    }

    @Override
    public List<WikiMessage> conversation(long userId, long otherUserId, int limit) {
        return rows(database.query("SELECT * FROM wiki_message"
                + " WHERE (from_user_id = ? AND to_user_id = ? AND from_deleted = ?)"
                + " OR (from_user_id = ? AND to_user_id = ? AND to_deleted = ?)"
                + " ORDER BY id ASC" + limitClause(limit),
                userId, otherUserId, false, otherUserId, userId, false));
    }

    @Override
    public long unreadCount(long userId) {
        return database.queryOne("SELECT COUNT(*) AS total FROM wiki_message"
                        + " WHERE to_user_id = ? AND to_deleted = ? AND read = ?",
                userId, false, false)
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    // ==================== 行 → 实体 ====================

    private static WikiMessage message(DbRow row) {
        WikiMessage message = new WikiMessage();
        message.setId(row.longOrNull("id"));
        message.setFromUserId(row.longOrNull("from_user_id"));
        message.setToUserId(row.longOrNull("to_user_id"));
        message.setContent(row.string("content"));
        message.setRead(row.boolValue("read", false));
        message.setReadAt(row.dateTime("read_at"));
        message.setFromDeleted(row.boolValue("from_deleted", false));
        message.setToDeleted(row.boolValue("to_deleted", false));
        message.setCreateAt(row.dateTime("create_at"));
        return message;
    }

    private static List<WikiMessage> rows(List<DbRow> rows) {
        List<WikiMessage> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(message(row));
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

    /**
     * 布尔入库：直接把 {@code Boolean} 交给驱动（H2 的 BOOLEAN 列不接受 0/1；
     * SQLite / MySQL 的驱动会把 true/false 写成 1/0），读取侧由 {@link DbRow} 归一化。
     */
    private static Boolean flag(Boolean value) {
        return value != null && value;
    }
}
