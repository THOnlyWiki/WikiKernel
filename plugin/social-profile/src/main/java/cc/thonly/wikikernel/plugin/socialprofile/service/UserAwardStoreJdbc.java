package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link UserAwardStore} 的插件实现：{@code wiki_user_award} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 只做数据访问：奖励清单与「谁能授予」在 {@link AwardService}。
 */
@Service
public class UserAwardStoreJdbc implements UserAwardStore {

    private final PluginDatabase database;

    public UserAwardStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiUserAward insert(WikiUserAward award) {
        long id = database.insert("INSERT INTO wiki_user_award"
                        + "(user_id, award_id, comment, granted_by, create_at) VALUES (?, ?, ?, ?, ?)",
                award.getUserId(), text(award.getAwardId()), text(award.getComment()),
                award.getGrantedBy(), award.getCreateAt());
        award.setId(id);
        return award;
    }

    @Override
    public Optional<WikiUserAward> find(long awardRowId) {
        return database.queryOne("SELECT * FROM wiki_user_award WHERE id = ?", awardRowId)
                .map(UserAwardStoreJdbc::award);
    }

    @Override
    public void delete(long awardRowId) {
        database.update("DELETE FROM wiki_user_award WHERE id = ?", awardRowId);
    }

    @Override
    public List<WikiUserAward> byUser(long userId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_award WHERE user_id = ?"
                + " ORDER BY id DESC" + limitClause(limit), userId));
    }

    @Override
    public boolean has(long userId, String awardId) {
        if (awardId == null || awardId.isBlank()) {
            return false;
        }
        return database.queryOne("SELECT COUNT(*) AS total FROM wiki_user_award"
                        + " WHERE user_id = ? AND award_id = ?", userId, awardId.trim())
                .map(row -> row.longValue("total", 0L))
                .orElse(0L) > 0;
    }

    @Override
    public Map<Long, Long> counts(Collection<Long> userIds) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        List<Long> wanted = ids(userIds);
        if (wanted.isEmpty()) {
            return counts;
        }
        for (DbRow row : database.query("SELECT user_id, COUNT(*) AS total FROM wiki_user_award"
                + " WHERE user_id IN (" + placeholders(wanted.size()) + ")"
                + " GROUP BY user_id ORDER BY MIN(id)", wanted.toArray())) {
            counts.put(row.longOrNull("user_id"), row.longValue("total", 0L));
        }
        return counts;
    }

    @Override
    public List<String> awardIdsOf(long userId) {
        List<String> ids = new ArrayList<>();
        for (WikiUserAward award : byUser(userId, 0)) {
            String awardId = award.getAwardId();
            if (awardId != null && !awardId.isBlank() && !ids.contains(awardId)) {
                ids.add(awardId);
            }
        }
        return ids;
    }

    // ==================== 行 → 实体 ====================

    private static WikiUserAward award(DbRow row) {
        WikiUserAward award = new WikiUserAward();
        award.setId(row.longOrNull("id"));
        award.setUserId(row.longOrNull("user_id"));
        award.setAwardId(row.string("award_id"));
        award.setComment(row.string("comment"));
        award.setGrantedBy(row.longOrNull("granted_by"));
        award.setCreateAt(row.dateTime("create_at"));
        return award;
    }

    private static List<WikiUserAward> rows(List<DbRow> rows) {
        List<WikiUserAward> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(award(row));
        }
        return result;
    }

    // ==================== 小工具 ====================

    /** {@code LIMIT n}（{@code limit <= 0} 时不限制；n 是内部 int，不来自用户输入）。 */
    private static String limitClause(int limit) {
        return limit > 0 ? " LIMIT " + limit : "";
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

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String text(String value) {
        return Strings.trimToEmpty(value);
    }
}
