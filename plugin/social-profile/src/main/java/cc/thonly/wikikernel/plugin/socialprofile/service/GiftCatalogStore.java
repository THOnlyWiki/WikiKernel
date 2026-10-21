package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiGift;

import java.util.List;
import java.util.Optional;

/** 自定义礼物目录的数据访问（{@code wiki_gift}）。 */
public interface GiftCatalogStore {

    WikiGift insert(WikiGift gift);

    Optional<WikiGift> find(long id);

    Optional<WikiGift> findByKey(String giftKey);

    List<WikiGift> all();

    void setEnabled(long id, boolean enabled);

    void delete(long id);
}
