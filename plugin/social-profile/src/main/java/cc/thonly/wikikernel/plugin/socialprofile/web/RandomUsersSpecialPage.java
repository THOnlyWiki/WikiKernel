package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:随机用户} / {@code Special:RandomUsersWithAvatars}（插件特殊页，对应 MediaWiki
 * SocialProfile 的 {@code Special:RandomUsersWithAvatars}）：随机抽几个**上传过头像**的用户展示。
 * <ul>
 *     <li>条数：{@code ?limit=N}（上限 {@code socialProfile.randomUsers.limit}，插件配置
 *     {@code randomUsers.limit}，默认 12）；</li>
 *     <li>数据来自 {@code common.service.UserStore.randomWithAvatar}（core 侧随机取一段有头像的用户，
 *     不是「全表拉回来再洗牌」）；</li>
 *     <li>每个用户显示头像、用户名、好友数与收到的礼物数（都是聚合计数，不拉明细）。</li>
 * </ul>
 */
public class RandomUsersSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    /** 默认展示几个。 */
    public static final int DEFAULT_LIMIT = 12;

    /** 一次最多展示几个（配置上限）。 */
    public static final int MAX_LIMIT = 48;

    public RandomUsersSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                                  BoardService board, FriendsService friends, GiftService gifts,
                                  AvatarSupport avatars) {
        super(users, support, profiles, board, friends, gifts, avatars);
    }

    @Override
    public String name() {
        return "随机用户";
    }

    @Override
    public String displayName() {
        return display("socialProfile.randomUsers.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("RandomUsers", "RandomUsersWithAvatars");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/random-users";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("featureDisabled", !support.enabled());
        if (!support.enabled()) {
            return;
        }
        int configured = Math.max(support.setting("randomUsers.limit", "socialProfile.randomUsers.limit",
                DEFAULT_LIMIT), 1);
        int max = Math.min(configured, MAX_LIMIT);
        int requested = parseLimit(Strings.trimToEmpty(request.parameters().get("limit")), configured);
        int limit = Math.min(Math.max(requested, 1), max);
        List<UserStore.UserRef> refs = users == null ? List.of() : users.randomWithAvatar(limit);
        List<Long> ids = new ArrayList<>();
        for (UserStore.UserRef ref : refs) {
            ids.add(ref.id());
        }
        Map<Long, Long> friendCounts = friends == null ? Map.of() : friends.friendCounts(ids);
        Map<Long, Long> giftCounts = gifts == null ? Map.of() : gifts.receivedCounts(ids);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserStore.UserRef ref : refs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ref.id());
            row.put("username", ref.username());
            row.put("userUrl", SocialProfileSupport.pagePath("用户:" + ref.username()));
            row.put("avatarUrl", ref.avatarUrl(UserStore.DEFAULT_AVATAR_URL));
            row.put("friendCount", friendCounts.getOrDefault(ref.id(), 0L));
            row.put("giftCount", giftCounts.getOrDefault(ref.id(), 0L));
            row.put("boardUrl", SocialProfileSupport.pagePath("特殊:留言板?user="
                    + SocialProfileSupport.encodeQuery(ref.username())));
            row.put("friendAddUrl", SocialProfileSupport.pagePath("特殊:好友?user="
                    + SocialProfileSupport.encodeQuery(ref.username())));
            row.put("giftUrl", SocialProfileSupport.pagePath("特殊:送礼物?user="
                    + SocialProfileSupport.encodeQuery(ref.username())));
            row.put("awardsUrl", SocialProfileSupport.pagePath("特殊:奖励?user="
                    + SocialProfileSupport.encodeQuery(ref.username())));
            rows.add(row);
        }
        model.put("randomUsers", rows);
        model.put("randomUsersLimit", limit);
        model.put("randomUsersMax", max);
        model.put("randomUsersReloadUrl", SocialProfileSupport.pagePath("特殊:随机用户"));
        model.put("topUsersUrl", SocialProfileSupport.pagePath("特殊:积分排行"));
    }

    private static int parseLimit(String value, int fallback) {
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
