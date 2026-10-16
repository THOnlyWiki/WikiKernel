package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.plugin.echo.data.WikiNotification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Echo 通知的数据访问（{@code wiki_notification} 表属于 Echo 插件自己）：
 * 表结构由 {@code EchoPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。只做数据访问，规则在
 * {@link EchoNotifier}（触发 / 去重 / 偏好过滤）。
 */
@Service
public class EchoNotificationStoreJdbc {

    private final PluginDatabase database;

    public EchoNotificationStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    /** 插入一条通知，返回自增 id。 */
    public long insert(WikiNotification notification) {
        long id = database.insert("INSERT INTO wiki_notification"
                        + "(recipient_user_id, type, actor_user_id, page_title, revision_id, comment,"
                        + " `read`, read_at, create_at, need_render, context_text)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                notification.getRecipientUserId(), notification.getType(), notification.getActorUserId(),
                notification.getPageTitle(), notification.getRevisionId(), notification.getComment(),
                flag(notification.getRead()), notification.getReadAt(), notification.getCreateAt(),
                flag(notification.getNeedRender()), notification.getContextText());
        notification.setId(id);
        return id;
    }

    /** 未读通知数（收件人的全部未读）。 */
    public long unreadCount(long recipientUserId) {
        return unreadCount(recipientUserId, null);
    }

    /** 未读通知数（可按类型集合过滤；null / 空 = 全部）。 */
    public long unreadCount(long recipientUserId, java.util.Collection<String> types) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM wiki_notification"
                + " WHERE recipient_user_id = ? AND `read` = ?");
        List<Object> args = new ArrayList<>();
        args.add(recipientUserId);
        args.add(false);
        appendTypes(sql, args, types);
        return database.queryOne(sql.toString(), args.toArray())
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    /**
     * 收件人的通知列表（最新在前）。
     *
     * @param type   通知类型过滤；null / 空 = 全部
     * @param offset 偏移（翻页）
     * @param limit  条数（{@code <= 0} = 默认上限）
     */
    public List<WikiNotification> list(long recipientUserId, String type, int offset, int limit) {
        return list(recipientUserId, type == null || type.isBlank() ? null : List.of(type.trim()), offset, limit);
    }

    /** 收件人的通知列表（可按类型集合过滤；null / 空 = 全部；最新在前）。 */
    public List<WikiNotification> list(long recipientUserId, java.util.Collection<String> types, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wiki_notification"
                + " WHERE recipient_user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(recipientUserId);
        appendTypes(sql, args, types);
        sql.append(" ORDER BY id DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
            if (offset > 0) {
                sql.append(" OFFSET ").append(offset);
            }
        }
        List<WikiNotification> rows = new ArrayList<>();
        for (DbRow row : database.query(sql.toString(), args.toArray())) {
            rows.add(notification(row));
        }
        return rows;
    }

    /** 收件人的通知总数（列表页翻页用）。 */
    public long count(long recipientUserId, String type) {
        return count(recipientUserId, type == null || type.isBlank() ? null : List.of(type.trim()));
    }

    /** 收件人的通知总数（可按类型集合过滤）。 */
    public long count(long recipientUserId, java.util.Collection<String> types) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM wiki_notification"
                + " WHERE recipient_user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(recipientUserId);
        appendTypes(sql, args, types);
        return database.queryOne(sql.toString(), args.toArray())
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    /** 追加 {@code AND type IN (?, ?, …)}（types 为空时不追加）。 */
    private static void appendTypes(StringBuilder sql, List<Object> args, java.util.Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        sql.append(" AND type IN (");
        int index = 0;
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            sql.append(index++ == 0 ? "?" : ", ?");
            args.add(type.trim());
        }
        sql.append(')');
    }

    /** 按 id 查一条通知（不存在返回空）。 */
    public Optional<WikiNotification> find(long id) {
        return database.queryOne("SELECT * FROM wiki_notification WHERE id = ?", id)
                .map(EchoNotificationStoreJdbc::notification);
    }

    /** 标记一条为已读（仅收件人本人可标）。 */
    public boolean markRead(long id, long recipientUserId) {
        int updated = database.update("UPDATE wiki_notification SET `read` = ?, read_at = ?"
                        + " WHERE id = ? AND recipient_user_id = ? AND `read` = ?",
                true, LocalDateTime.now(), id, recipientUserId, false);
        return updated > 0;
    }

    /** 标记某收件人全部为已读。 */
    public void markAllRead(long recipientUserId) {
        markAllRead(recipientUserId, null);
    }

    /** 标记某收件人某组类型为已读（types 为空 = 全部）。 */
    public void markAllRead(long recipientUserId, java.util.Collection<String> types) {
        StringBuilder sql = new StringBuilder("UPDATE wiki_notification SET `read` = ?, read_at = ?"
                + " WHERE recipient_user_id = ? AND `read` = ?");
        List<Object> args = new ArrayList<>();
        args.add(true);
        args.add(LocalDateTime.now());
        args.add(recipientUserId);
        args.add(false);
        appendTypes(sql, args, types);
        database.update(sql.toString(), args.toArray());
    }

    // ==================== 行 → 实体 ====================

    private static WikiNotification notification(DbRow row) {
        WikiNotification notification = new WikiNotification();
        notification.setId(row.longOrNull("id"));
        notification.setRecipientUserId(row.longOrNull("recipient_user_id"));
        notification.setType(row.string("type"));
        notification.setActorUserId(row.longOrNull("actor_user_id"));
        notification.setPageTitle(row.string("page_title"));
        notification.setRevisionId(row.longOrNull("revision_id"));
        notification.setComment(row.string("comment"));
        notification.setRead(row.boolValue("read", false));
        notification.setReadAt(row.dateTime("read_at"));
        notification.setCreateAt(row.dateTime("create_at"));
        notification.setNeedRender(row.boolValue("need_render", false));
        notification.setContextText(row.string("context_text"));
        return notification;
    }

    private static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }
}