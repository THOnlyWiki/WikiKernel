package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积分与等级的给分入口（仿 MediaWiki SocialProfile 的 {@code UserStats} / {@code UserLevel}）。
 *
 * <h2>给分入口的选择与局限（重要）</h2>
 * core 目前**没有**「页面保存成功」这类给插件的事件（{@code EditGuard} 只在保存前拦一道，
 * 拿不到「已经保存成功」的时机），所以本插件用两个入口：
 * <ol>
 *     <li><b>社交动作</b>（留言 / 加好友 / 送礼物 / 保存资料）：在插件自己的服务里直接
 *     {@link #award(long, PointsRules.Action)} —— 精确、无重复；</li>
 *     <li><b>页面浏览</b>（编辑行为的替代）：注册 core 的 {@code common.extension.PageViewHook}
 *     （与 HitCounters 同一个钩子，不新增 core 事件），只在词条阅读视图成功渲染后触发；
 *     同一个用户对同一个页面在 {@code socialProfile.points.viewCooldownSeconds} 秒内（默认 300 秒）
 *     只计一次，避免刷新刷分（去重表是进程内的，重启后第一次浏览会重新计分）。</li>
 * </ol>
 * <b>局限</b>：浏览 ≠ 编辑，所以「编辑页面给分」是**用浏览代偿**的近似；要给真正的编辑行为给分，
 * 需要在 core 增加一个「编辑完成」事件 / 钩子（本轮刻意不改 core 行为，只加数据桥），
 * 换入口时只要把 {@code PointsViewHook} 换成新的钩子实现即可，给分逻辑与阈值表不用动。
 *
 * <h2>等级</h2>
 * 每次给分后按 {@link PointsRules#levelFor(long)} 算出等级并**一起落库**到
 * {@code wiki_user_stats.level}（排行榜直接读这一列，不必自己再算阈值）；阈值表调整后，
 * 用户下一次拿到积分时等级会自动刷新（也可以在「特殊:积分排行」里看到旧等级，直到下次给分）。
 */
@Component
public final class PointsService {

    /** 页面浏览去重表的键上限（超过后先清空，避免长期运行内存增长）。 */
    private static final int VIEW_CACHE_LIMIT = 20_000;

    private final UserStatsStore stats;
    private final SocialProfileSupport support;
    private final PointsRulesProvider rulesProvider;

    /** 页面浏览去重（键 {@code userId + "|" + pageId} → 上次计分时间毫秒）。 */
    private final Map<String, Long> lastViewAward = new ConcurrentHashMap<>();

    /** 进程内事件队列（最近给分记录，供调试 / 说明用；不落库）。 */
    private final Deque<String> recentEvents = new ArrayDeque<>();

    public PointsService(UserStatsStore stats, SocialProfileSupport support) {
        this(stats, support, null);
    }

    /**
     * @param stats         积分数据桥
     * @param support       开关与文案
     * @param rulesProvider 给分规则提供者（{@code @Component}；为 null 时退回 {@code support.pointsRules()}）
     */
    @Autowired
    public PointsService(UserStatsStore stats, SocialProfileSupport support, PointsRulesProvider rulesProvider) {
        this.stats = stats;
        this.support = support;
        this.rulesProvider = rulesProvider;
    }

    /** 当前给分规则（站点设置 / 插件配置，见 {@link PointsRules}）。 */
    public PointsRules rules() {
        if (rulesProvider != null) {
            return rulesProvider.rules();
        }
        return support == null ? PointsRules.DISABLED : support.pointsRules();
    }

    /** 等级名（消息键 {@code socialProfile.level.<n>}）。 */
    public String levelName(int level) {
        return support == null ? "socialProfile.level." + level : support.levelName(level);
    }

    /**
     * 给某个用户加一次该动作的积分（分值 0 / 给分关闭 / 用户不存在时什么都不做）。
     *
     * @return 加完之后的统计行；没给分时为空
     */
    public WikiUserStats award(long userId, PointsRules.Action action) {
        if (userId <= 0 || action == null) {
            return null;
        }
        int points = rules().pointsFor(action);
        if (points <= 0 || stats == null) {
            return null;
        }
        return awardPoints(userId, points, action.name());
    }

    /** 直接给分（页面浏览去重后调用；{@code reason} 只用于进程内事件记录）。 */
    public WikiUserStats awardPoints(long userId, int points, String reason) {
        if (userId <= 0 || points <= 0 || stats == null) {
            return null;
        }
        WikiUserStats row = stats.addPoints(userId, points);
        if (row == null) {
            return null;
        }
        long total = row.getPoints() == null ? 0L : row.getPoints();
        int level = rules().levelFor(total);
        if (row.getLevel() == null || row.getLevel() != level) {
            row = stats.setPoints(userId, total, level);
        }
        record(userId, points, reason);
        return row;
    }

    /**
     * 页面浏览给分（{@code PageViewHook} 调用）：登录用户 + 非机器人 + 冷却期外才计分。
     *
     * @param userId  浏览者（{@code <= 0} = 匿名，直接跳过）
     * @param pageId  页面 ID
     * @param bot     是否机器人
     * @return 这次是否真的给了分
     */
    public boolean awardPageView(long userId, long pageId, boolean bot) {
        if (userId <= 0 || bot || pageId <= 0) {
            return false;
        }
        int points = rules().pointsFor(PointsRules.Action.PAGE_VIEW);
        if (points <= 0) {
            return false;
        }
        int cooldown = Math.max(rules().viewCooldownSeconds(), 0);
        String key = userId + "|" + pageId;
        long now = System.currentTimeMillis();
        if (cooldown > 0) {
            Long previous = lastViewAward.get(key);
            if (previous != null && now - previous < cooldown * 1000L) {
                return false;
            }
        }
        if (lastViewAward.size() > VIEW_CACHE_LIMIT) {
            lastViewAward.clear();
        }
        lastViewAward.put(key, now);
        return awardPoints(userId, points, PointsRules.Action.PAGE_VIEW.name()) != null;
    }

    /** 某个用户的积分行（没有获得过积分时为空）。 */
    public Optional<WikiUserStats> of(long userId) {
        return stats == null ? Optional.empty() : stats.byUser(userId);
    }

    /** 某个用户的积分（没有记录 = 0）。 */
    public long pointsOf(long userId) {
        return of(userId).map(row -> row.getPoints() == null ? 0L : row.getPoints()).orElse(0L);
    }

    /** 某个用户的等级（没有记录 = 1 级）。 */
    public int levelOf(long userId) {
        return of(userId).map(row -> row.getLevel() == null ? 1 : row.getLevel()).orElse(1);
    }

    /** 一批用户的积分行（榜单 / 列表用）。 */
    public Map<Long, WikiUserStats> ofUsers(Collection<Long> userIds) {
        return stats == null ? Map.of() : stats.byUsers(userIds);
    }
    /**
     * 积分排行榜（按积分倒序）。
     *
     * @param offset 跳过前 N 名
     * @param limit  取多少名
     */
    public List<UserStatsStore.Entry> top(int offset, int limit) {
        return stats == null ? List.of() : stats.top(offset, limit);
    }

    /** 有积分记录的用户数（分页用）。 */
    public long rankedCount() {
        return stats == null ? 0 : stats.count();
    }

    /** 手工重算某个用户的等级（阈值表调整后调用）。 */
    public WikiUserStats refreshLevel(long userId) {
        if (userId <= 0 || stats == null) {
            return null;
        }
        WikiUserStats row = stats.byUser(userId).orElse(null);
        if (row == null) {
            return null;
        }
        long total = row.getPoints() == null ? 0L : row.getPoints();
        int level = rules().levelFor(total);
        if (row.getLevel() != null && row.getLevel() == level) {
            return row;
        }
        return stats.setPoints(userId, total, level);
    }

    /** 最近给分事件（进程内，最多 50 条；只用于诊断，不作为数据源）。 */
    public List<String> recentEvents() {
        synchronized (recentEvents) {
            return List.copyOf(recentEvents);
        }
    }

    private void record(long userId, int points, String reason) {
        synchronized (recentEvents) {
            recentEvents.addFirst("user=" + userId + " +" + points + " (" + reason + ")");
            while (recentEvents.size() > 50) {
                recentEvents.removeLast();
            }
        }
    }
}
