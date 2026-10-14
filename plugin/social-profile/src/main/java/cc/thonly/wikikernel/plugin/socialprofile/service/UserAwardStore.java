package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 奖励 / 勋章数据访问（{@code wiki_user_award} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据。奖励清单、授予权限（管理员）由 {@link AwardService} 负责；实现见 {@link UserAwardStoreJdbc}。
 */
public interface UserAwardStore {

    /** 插入一条授予记录（返回带自增 ID 的实体）。 */
    WikiUserAward insert(WikiUserAward award);

    /** 按 ID 查授予记录。 */
    Optional<WikiUserAward> find(long awardRowId);

    /** 物理删除（撤销奖励）。 */
    void delete(long awardRowId);

    /** 某人获得的奖励，新的在前。 */
    List<WikiUserAward> byUser(long userId, int limit);

    /** 某人是否已获得某种奖励（同一种奖励不重复授予）。 */
    boolean has(long userId, String awardId);

    /** 一批用户各自获得的奖励总数（列表 / 资料卡显示「N 枚勋章」用）。 */
    Map<Long, Long> counts(Collection<Long> userIds);

    /** 某人的全部奖励种类 id（去重；资料卡徽章行用）。 */
    List<String> awardIdsOf(long userId);
}
