package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link UserStatsStore} 的插件实现：{@code wiki_user_stats} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 给分用 {@code UPDATE ... SET points = points + ?} 的原子自增（并发下不会丢分），
 * 等级由 {@link PointsService} 按阈值表算好后一起落库；排行榜在这里补齐用户名与头像
 * （走 {@link UserStore} 桥，插件不直接摸 core 的用户表）。
 */
@Service
public class UserStatsStoreJdbc implements UserStatsStore {

    private final PluginDatabase database;
    private final UserStore users;

    public UserStatsStoreJdbc(PluginDatabase database, UserStore users) {
        this.database = database;
        this.users = users;
    }

    @Override
    public WikiUserStats addPoints(long userId, long delta) {
        if (userId <= 0) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = database.update("UPDATE wiki_user_stats"
                + " SET points = points + ?, update_at = ? WHERE user_id = ?", delta, now, userId);
        if (updated == 0) {
            if (delta <= 0) {
                // 扣分为负数时不给从没得过分的用户凭空建行（避免积分为 0 的排行榜噪声行）
                return null;
            }
            WikiUserStats created = new WikiUserStats(null, userId, delta, 1, now);
            created.setId(database.insert("INSERT INTO wiki_user_stats"
                            + "(user_id, points, level, update_at) VALUES (?, ?, ?, ?)",
                    userId, delta, 1, now));
            return created;
        }
        return byUser(userId).orElse(null);
    }

    @Override
    public WikiUserStats setPoints(long userId, long points, int level) {
        if (userId <= 0) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        WikiUserStats existing = byUser(userId).orElse(null);
        if (existing == null) {
            WikiUserStats created = new WikiUserStats(null, userId, Math.max(points, 0L),
                    Math.max(level, 1), now);
            created.setId(database.insert("INSERT INTO wiki_user_stats"
                            + "(user_id, points, level, update_at) VALUES (?, ?, ?, ?)",
                    userId, created.getPoints(), created.getLevel(), now));
            return created;
        }
        existing.setPoints(Math.max(points, 0L));
        existing.setLevel(Math.max(level, 1));
        existing.setUpdateAt(now);
        database.update("UPDATE wiki_user_stats SET points = ?, level = ?, update_at = ? WHERE id = ?",
                existing.getPoints(), existing.getLevel(), now, existing.getId());
        return existing;
    }

    @Override
    public Optional<WikiUserStats> byUser(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return database.queryOne("SELECT * FROM wiki_user_stats"
                        + " WHERE user_id = ? ORDER BY id ASC LIMIT 1", userId)
                .map(UserStatsStoreJdbc::stats);
    }

    @Override
    public Map<Long, WikiUserStats> byUsers(Collection<Long> userIds) {
        Map<Long, WikiUserStats> rows = new LinkedHashMap<>();
        Set<Long> wanted = new LinkedHashSet<>();
        if (userIds != null) {
            for (Long id : userIds) {
                if (id != null) {
                    wanted.add(id);
                }
            }
        }
        if (wanted.isEmpty()) {
            return rows;
        }
        for (DbRow row : database.query("SELECT * FROM wiki_user_stats"
                + " WHERE user_id IN (" + placeholders(wanted.size()) + ")", wanted.toArray())) {
            WikiUserStats stats = stats(row);
            rows.put(stats.getUserId(), stats);
        }
        return rows;
    }

    @Override
    public List<Entry> top(int offset, int limit) {
        List<WikiUserStats> rows = new ArrayList<>();
        for (DbRow row : database.query("SELECT * FROM wiki_user_stats"
                + " ORDER BY points DESC, level DESC, user_id ASC" + pageClause(offset, limit))) {
            rows.add(stats(row));
        }
        return entries(rows);
    }

    @Override
    public long count() {
        return database.queryOne("SELECT COUNT(*) AS total FROM wiki_user_stats")
                .map(row -> row.longValue("total", 0L))
                .orElse(0L);
    }

    /** 给排行榜行补齐用户名与头像（一次批量查用户，避免 N+1）。 */
    private List<Entry> entries(List<WikiUserStats> rows) {
        List<Entry> entries = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return entries;
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiUserStats stats : rows) {
            userIds.add(stats.getUserId());
        }
        Map<Long, String> names = users.names(userIds);
        Map<Long, String> avatarFiles = users.avatars(userIds);
        for (WikiUserStats stats : rows) {
            String username = names.get(stats.getUserId());
            String avatar = avatarFiles.get(stats.getUserId());
            String avatarUrl = avatar == null || avatar.isEmpty()
                    ? UserStore.DEFAULT_AVATAR_URL
                    : "/avatars/" + avatar;
            long points = stats.getPoints() == null ? 0L : stats.getPoints();
            int level = stats.getLevel() == null ? 1 : stats.getLevel();
            entries.add(new Entry(stats.getUserId(), username == null ? "" : username, avatarUrl, points, level));
        }
        return entries;
    }

    // ==================== 行 → 实体 ====================

    private static WikiUserStats stats(DbRow row) {
        WikiUserStats stats = new WikiUserStats();
        stats.setId(row.longOrNull("id"));
        stats.setUserId(row.longOrNull("user_id"));
        stats.setPoints(row.longValue("points", 0L));
        stats.setLevel(row.intValue("level", 1));
        stats.setUpdateAt(row.dateTime("update_at"));
        return stats;
    }

    // ==================== 小工具 ====================

    /** 分页子句：{@code LIMIT n OFFSET m}（都不限制时返回空串；n / m 是内部 int，不来自用户输入）。 */
    private static String pageClause(int offset, int limit) {
        if (limit <= 0 && offset <= 0) {
            return "";
        }
        StringBuilder clause = new StringBuilder();
        if (limit > 0) {
            clause.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            clause.append(" OFFSET ").append(offset);
        }
        return clause.toString();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
