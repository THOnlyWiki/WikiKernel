package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 礼物数据访问（{@code wiki_user_gift} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据：送出的礼物记录。礼物种类清单、能否送给自己、限流等规则由 {@link GiftService} 负责；
 * 实现见 {@link UserGiftStoreJdbc}。
 */
public interface UserGiftStore {

    /** 插入礼物记录（返回带自增 ID 的实体）。 */
    WikiUserGift insert(WikiUserGift gift);

    /** 按 ID 查礼物。 */
    Optional<WikiUserGift> find(long giftId);

    /** 物理删除（管理员清理 / 撤销礼物）。 */
    void delete(long giftId);

    /** 某人**收到**的礼物，新的在前。 */
    List<WikiUserGift> received(long toUserId, int limit);

    /** 某人**送出**的礼物，新的在前。 */
    List<WikiUserGift> sent(long fromUserId, int limit);

    /**
     * 某人的收礼计数（按礼物种类）：键是礼物 id，值是件数；没有收到过返回空表。
     * <p>
     * 用于用户页「收到 N 个礼物」与展开清单（按种类汇总，避免一次拉全部记录）。
     */
    Map<String, Long> countReceivedByGift(long toUserId);

    /** 一批用户各自收到的礼物总数（列表里显示「N 个礼物」用）。 */
    Map<Long, Long> receivedCounts(Collection<Long> userIds);
}
