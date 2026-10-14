package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link UserRelationStore} 的插件实现：{@code wiki_user_relation} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 只做数据访问：请求 → 接受 / 拒绝 / 取消等规则在 {@link FriendsService}。
 */
@Service
public class UserRelationStoreJdbc implements UserRelationStore {

    private final PluginDatabase database;

    public UserRelationStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiUserRelation insert(WikiUserRelation relation) {
        long id = database.insert("INSERT INTO wiki_user_relation"
                        + "(from_user_id, to_user_id, relation_type, status, create_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                relation.getFromUserId(), relation.getToUserId(), text(relation.getRelationType()),
                text(relation.getStatus()), relation.getCreateAt());
        relation.setId(id);
        return relation;
    }

    @Override
    public Optional<WikiUserRelation> find(long relationId) {
        return database.queryOne("SELECT * FROM wiki_user_relation WHERE id = ?", relationId)
                .map(UserRelationStoreJdbc::relation);
    }

    @Override
    public void update(WikiUserRelation relation) {
        // 与原 MyBatis-Plus 的 updateById 一致：只更新非空字段（null = 不改这一列）
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(assignments, args, "from_user_id", relation.getFromUserId());
        add(assignments, args, "to_user_id", relation.getToUserId());
        add(assignments, args, "relation_type", relation.getRelationType());
        add(assignments, args, "status", relation.getStatus());
        add(assignments, args, "create_at", relation.getCreateAt());
        if (!assignments.isEmpty()) {
            args.add(relation.getId());
            database.update("UPDATE wiki_user_relation SET " + String.join(", ", assignments) + " WHERE id = ?",
                    args.toArray());
        }
    }

    @Override
    public void delete(long relationId) {
        database.update("DELETE FROM wiki_user_relation WHERE id = ?", relationId);
    }

    @Override
    public Optional<WikiUserRelation> find(long fromUserId, long toUserId, String relationType) {
        return database.queryOne("SELECT * FROM wiki_user_relation"
                        + " WHERE from_user_id = ? AND to_user_id = ? AND relation_type = ?"
                        + " ORDER BY id ASC LIMIT 1",
                fromUserId, toUserId, text(relationType)).map(UserRelationStoreJdbc::relation);
    }

    @Override
    public List<WikiUserRelation> outgoing(long fromUserId, String relationType, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_relation"
                + " WHERE from_user_id = ? AND relation_type = ?"
                + " ORDER BY id DESC" + limitClause(limit), fromUserId, text(relationType)));
    }

    @Override
    public List<WikiUserRelation> incoming(long toUserId, String relationType, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_relation"
                + " WHERE to_user_id = ? AND relation_type = ?"
                + " ORDER BY id DESC" + limitClause(limit), toUserId, text(relationType)));
    }

    @Override
    public long pendingFriendCount(long toUserId) {
        return database.queryOne("SELECT COUNT(*) AS total FROM wiki_user_relation"
                        + " WHERE to_user_id = ? AND relation_type = ? AND status = ?",
                toUserId, WikiUserRelation.TYPE_FRIEND, WikiUserRelation.STATUS_PENDING)
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    @Override
    public Map<Long, Long> friendCounts(Collection<Long> userIds) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        List<Long> wanted = ids(userIds);
        if (wanted.isEmpty()) {
            return counts;
        }
        // 好友关系是双向两行（插件在「接受」时补反向 ACCEPTED 行），所以按 from 方向数一遍就是好友数
        for (DbRow row : database.query("SELECT from_user_id, COUNT(*) AS total FROM wiki_user_relation"
                + " WHERE from_user_id IN (" + placeholders(wanted.size()) + ")"
                + " AND relation_type = ? AND status = ?"
                + " GROUP BY from_user_id ORDER BY MIN(id)",
                concat(wanted, WikiUserRelation.TYPE_FRIEND, WikiUserRelation.STATUS_ACCEPTED))) {
            counts.put(row.longOrNull("from_user_id"), row.longValue("total", 0L));
        }
        return counts;
    }

    // ==================== 行 → 实体 ====================

    private static WikiUserRelation relation(DbRow row) {
        WikiUserRelation relation = new WikiUserRelation();
        relation.setId(row.longOrNull("id"));
        relation.setFromUserId(row.longOrNull("from_user_id"));
        relation.setToUserId(row.longOrNull("to_user_id"));
        relation.setRelationType(row.string("relation_type"));
        relation.setStatus(row.string("status"));
        relation.setCreateAt(row.dateTime("create_at"));
        return relation;
    }

    private static List<WikiUserRelation> rows(List<DbRow> rows) {
        List<WikiUserRelation> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(relation(row));
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

    /** 过滤 {@code null} 的 ID 列表（与 MyBatis-Plus 的 {@code in(null)} 一样匹配不到任何行）。 */
    private static List<Long> ids(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> wanted = new ArrayList<>(userIds.size());
        for (Long id : userIds) {
            if (id != null) {
                wanted.add(id);
            }
        }
        return wanted;
    }

    /** ID 列表 + 额外固定参数（{@code IN (...)} 的参数顺序）。 */
    private static Object[] concat(List<Long> ids, Object... extra) {
        Object[] args = new Object[ids.size() + extra.length];
        for (int index = 0; index < ids.size(); index++) {
            args[index] = ids.get(index);
        }
        System.arraycopy(extra, 0, args, ids.size(), extra.length);
        return args;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String text(String value) {
        return Strings.trimToEmpty(value);
    }
}
