package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户积分 / 等级 / 排行榜数据访问（{@code wiki_user_stats} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据：给分、读一个人的积分、读排行榜。给分规则（哪些动作给多少分）与等级阈值表由
 * {@link PointsService} / {@link PointsRules} 负责，给分时把算好的 {@code level} 一起落库；
 * 实现见 {@link UserStatsStoreJdbc}。
 */
public interface UserStatsStore {

    /**
     * 排行榜上的一行（用户名与头像由实现一并补齐，插件不必再查一次用户表）。
     *
     * @param userId    用户 ID
     * @param username  用户名（找不到用户时为空串）
     * @param avatarUrl 头像 URL（未上传 = {@link UserStore#DEFAULT_AVATAR_URL}）
     * @param points    积分
     * @param level     等级（按 {@code wiki_user_stats.level} 原样读出）
     */
    record Entry(long userId, String username, String avatarUrl, long points, int level) {
    }

    /** 给某个用户加（或减）积分；返回加完之后的统计行（用户第一次获得积分时自动建行）。 */
    WikiUserStats addPoints(long userId, long delta);

    /** 直接写某个用户的积分与等级（阈值表调整后的重算用；用户没有行时建行）。 */
    WikiUserStats setPoints(long userId, long points, int level);

    /** 按用户 ID 查积分行（没有获得过积分时为空）。 */
    Optional<WikiUserStats> byUser(long userId);

    /** 一批用户的积分行（找不到的用户不出现）。 */
    Map<Long, WikiUserStats> byUsers(Collection<Long> userIds);

    /**
     * 积分排行榜（按积分倒序、同分按等级倒序、再按用户 ID 升序稳定排序）。
     *
     * @param offset 跳过前 N 名
     * @param limit  取多少名（{@code <= 0} = 不限制）
     */
    List<Entry> top(int offset, int limit);

    /** 有积分记录的用户总数（排行榜分页用）。 */
    long count();
}
