package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link UserGiftStore} 的插件实现：{@code wiki_user_gift} 表**属于插件自己**——
 * 表结构由 {@code SocialProfilePlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，
 * 数据访问走 {@link PluginDatabase}（JDBC 桥）。
 * <p>
 * 只做数据访问：礼物清单、能否送给自己、限流等规则在 {@link GiftService}；
 * 计数用 SQL {@code GROUP BY} 汇总（不一次拉全部记录）。
 */
@Service
public class UserGiftStoreJdbc implements UserGiftStore {

    private final PluginDatabase database;

    public UserGiftStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiUserGift insert(WikiUserGift gift) {
        long id = database.insert("INSERT INTO wiki_user_gift"
                        + "(from_user_id, to_user_id, gift_id, message, create_at) VALUES (?, ?, ?, ?, ?)",
                gift.getFromUserId(), gift.getToUserId(), text(gift.getGiftId()),
                text(gift.getMessage()), gift.getCreateAt());
        gift.setId(id);
        return gift;
    }

    @Override
    public Optional<WikiUserGift> find(long giftId) {
        return database.queryOne("SELECT * FROM wiki_user_gift WHERE id = ?", giftId)
                .map(UserGiftStoreJdbc::gift);
    }

    @Override
    public void delete(long giftId) {
        database.update("DELETE FROM wiki_user_gift WHERE id = ?", giftId);
    }

    @Override
    public List<WikiUserGift> received(long toUserId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_gift WHERE to_user_id = ?"
                + " ORDER BY id DESC" + limitClause(limit), toUserId));
    }

    @Override
    public List<WikiUserGift> sent(long fromUserId, int limit) {
        return rows(database.query("SELECT * FROM wiki_user_gift WHERE from_user_id = ?"
                + " ORDER BY id DESC" + limitClause(limit), fromUserId));
    }

    @Override
    public Map<String, Long> countReceivedByGift(long toUserId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DbRow row : database.query("SELECT gift_id, COUNT(*) AS total FROM wiki_user_gift"
                + " WHERE to_user_id = ? GROUP BY gift_id ORDER BY MIN(id)", toUserId)) {
            counts.put(row.string("gift_id"), row.longValue("total", 0L));
        }
        return counts;
    }

    @Override
    public Map<Long, Long> receivedCounts(Collection<Long> userIds) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        List<Long> wanted = ids(userIds);
        if (wanted.isEmpty()) {
            return counts;
        }
        for (DbRow row : database.query("SELECT to_user_id, COUNT(*) AS total FROM wiki_user_gift"
                + " WHERE to_user_id IN (" + placeholders(wanted.size()) + ")"
                + " GROUP BY to_user_id ORDER BY MIN(id)", wanted.toArray())) {
            counts.put(row.longOrNull("to_user_id"), row.longValue("total", 0L));
        }
        return counts;
    }

    // ==================== 行 → 实体 ====================

    private static WikiUserGift gift(DbRow row) {
        WikiUserGift gift = new WikiUserGift();
        gift.setId(row.longOrNull("id"));
        gift.setFromUserId(row.longOrNull("from_user_id"));
        gift.setToUserId(row.longOrNull("to_user_id"));
        gift.setGiftId(row.string("gift_id"));
        gift.setMessage(row.string("message"));
        gift.setCreateAt(row.dateTime("create_at"));
        return gift;
    }

    private static List<WikiUserGift> rows(List<DbRow> rows) {
        List<WikiUserGift> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(gift(row));
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
