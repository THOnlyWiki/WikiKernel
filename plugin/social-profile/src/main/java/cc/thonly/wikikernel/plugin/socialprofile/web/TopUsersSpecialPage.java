package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsRules;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsRulesProvider;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserStatsStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:积分排行} / {@code Special:TopUsers}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:TopUsers}）：按积分排序的前 N 名（含头像、等级、积分）。
 * <ul>
 *     <li>每页条数：{@code socialProfile.topUsers.pageSize}（插件配置 {@code topUsers.pageSize}），默认 20；</li>
 *     <li>最多展示：{@code socialProfile.topUsers.limit}（插件配置 {@code topUsers.limit}），默认 200；</li>
 *     <li>分页：{@code ?offset=20}；榜单数据来自 {@code common.service.UserStatsStore.top}。</li>
 * </ul>
 * 给分规则与等级阈值表见 {@link PointsRules}（默认每浏览 1 分 / 留言 5 分 / 加好友 10 分 / 送礼 3 分 /
 * 存资料 2 分；等级阈值 0 / 50 / 200 / 500 / 1000）。
 */
public class TopUsersSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    /** 一页默认条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 默认最多展示的名次。 */
    public static final int DEFAULT_LIMIT = 200;

    private final PointsService points;
    private final UserStatsStore stats;
    private final PointsRulesProvider rulesProvider;

    public TopUsersSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                               BoardService board, FriendsService friends, GiftService gifts,
                               AvatarSupport avatars, PointsService points, UserStatsStore stats,
                               PointsRulesProvider rulesProvider) {
        super(users, support, profiles, board, friends, gifts, avatars);
        this.points = points;
        this.stats = stats;
        this.rulesProvider = rulesProvider;
    }

    @Override
    public String name() {
        return "积分排行";
    }

    @Override
    public String displayName() {
        return display("socialProfile.topUsers.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("TopUsers", "Leaderboard", "Points");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/top-users";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        boolean disabled = !support.enabled();
        model.put("featureDisabled", disabled);
        if (disabled) {
            return;
        }
        int pageSize = Math.max(support.setting("topUsers.pageSize", "socialProfile.topUsers.pageSize",
                DEFAULT_PAGE_SIZE), 1);
        int maxLimit = Math.max(support.setting("topUsers.limit", "socialProfile.topUsers.limit",
                DEFAULT_LIMIT), 1);
        int offset = parseOffset(Strings.trimToEmpty(request.parameters().get("offset")));
        offset = Math.min(Math.max(offset, 0), Math.max(maxLimit - 1, 0));
        int limit = Math.min(pageSize, Math.max(maxLimit - offset, 0));
        List<UserStatsStore.Entry> entries = limit <= 0 ? List.of() : points.top(offset, limit);
        long total = points.rankedCount();
        model.put("topUsers", toRows(entries, offset));
        model.put("topOffset", offset);
        model.put("topPageSize", pageSize);
        model.put("topTotal", Math.min(total, maxLimit));
        model.put("topRankedCount", total);
        model.put("topPrevUrl", offset > 0
                ? SocialProfileSupport.pagePath("特殊:积分排行?offset=" + Math.max(offset - pageSize, 0)) : null);
        model.put("topNextUrl", offset + pageSize < Math.min(total, maxLimit)
                ? SocialProfileSupport.pagePath("特殊:积分排行?offset=" + (offset + pageSize)) : null);
        model.put("topRandomUsersUrl", SocialProfileSupport.pagePath("特殊:随机用户"));
        // 给分规则 / 等级阈值一并展示（站点设置 socialProfile.points.* 里可改）
        PointsRules rules = rulesProvider == null ? points.rules() : rulesProvider.rules();
        model.put("pointsRules", rules);
        model.put("pointsLevels", rules.levels());
        model.put("pointsEnabled", rules.enabled());
    }

    /** 榜单行（名次从 1 开始，跨页连续）。 */
    private List<Map<String, Object>> toRows(List<UserStatsStore.Entry> entries, int offset) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = offset + 1;
        for (UserStatsStore.Entry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("userId", entry.userId());
            row.put("username", entry.username());
            row.put("userUrl", SocialProfileSupport.pagePath("用户:" + entry.username()));
            row.put("avatarUrl", entry.avatarUrl());
            row.put("points", entry.points());
            int level = entry.level() > 0 ? entry.level() : points.rules().levelFor(entry.points());            row.put("level", level);
            row.put("levelName", points.levelName(level));
            rows.add(row);
        }
        return rows;
    }

    /** 某个用户的积分行（个人成绩条用；没有记录返回 null）。 */
    public WikiUserStats statsOf(long userId) {
        return stats == null ? null : stats.byUser(userId).orElse(null);
    }

    private static int parseOffset(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
