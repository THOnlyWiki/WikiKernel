package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiGift;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link GiftCatalogStore} 的 JDBC 实现（插件自有表 {@code wiki_gift}）。 */
@Service
public class GiftCatalogStoreJdbc implements GiftCatalogStore {

    private final PluginDatabase database;

    public GiftCatalogStoreJdbc(PluginDatabase database) {
        this.database = database;
    }

    @Override
    public WikiGift insert(WikiGift gift) {
        long id = database.insert("INSERT INTO wiki_gift"
                        + "(gift_key, name, description, icon, created_by, enabled, create_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                text(gift.getGiftKey()), text(gift.getName()), text(gift.getDescription()),
                text(gift.getIcon()), gift.getCreatedBy(),
                Boolean.TRUE.equals(gift.getEnabled()) ? 1 : 0, gift.getCreateAt());
        gift.setId(id);
        return gift;
    }

    @Override
    public Optional<WikiGift> find(long id) {
        return database.queryOne("SELECT * FROM wiki_gift WHERE id = ?", id).map(GiftCatalogStoreJdbc::gift);
    }

    @Override
    public Optional<WikiGift> findByKey(String giftKey) {
        return database.queryOne("SELECT * FROM wiki_gift WHERE gift_key = ?", text(giftKey))
                .map(GiftCatalogStoreJdbc::gift);
    }

    @Override
    public List<WikiGift> all() {
        List<WikiGift> rows = new ArrayList<>();
        for (DbRow row : database.query("SELECT * FROM wiki_gift ORDER BY id ASC")) {
            rows.add(gift(row));
        }
        return rows;
    }

    @Override
    public void setEnabled(long id, boolean enabled) {
        database.update("UPDATE wiki_gift SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    @Override
    public void delete(long id) {
        database.update("DELETE FROM wiki_gift WHERE id = ?", id);
    }

    private static WikiGift gift(DbRow row) {
        WikiGift gift = new WikiGift();
        gift.setId(row.longOrNull("id"));
        gift.setGiftKey(row.string("gift_key"));
        gift.setName(row.string("name"));
        gift.setDescription(row.string("description"));
        gift.setIcon(row.string("icon"));
        gift.setCreatedBy(row.longOrNull("created_by"));
        gift.setEnabled(row.boolValue("enabled", true));
        gift.setCreateAt(row.dateTime("create_at"));
        return gift;
    }

    private static String text(String value) {
        return Strings.trimToEmpty(value);
    }
}
