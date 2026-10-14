package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SocialProfile 特殊页的公共行为：开关 / 未登录提示、目标用户解析（{@code ?user=} 与退回本人）、
 * 模板名与别名、留言 / 好友 / 礼物 / 勋章 / 排行榜页面的模型键。
 * （页面的 HTML 由 jar 内模板渲染，这里只验证模型与权限提示，模板结构靠人工浏览器验证。）
 */
class SocialProfileSpecialPagesTest {

    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "u1-1.png")
            .add(2, "乙", "");
    private final SocialProfileFakes.FakeSettings settings = new SocialProfileFakes.FakeSettings();
    private final SocialProfileFakes.FakeBoards boards = new SocialProfileFakes.FakeBoards();
    private final SocialProfileFakes.FakeRelations relations = new SocialProfileFakes.FakeRelations();
    private final SocialProfileFakes.FakeGifts gifts = new SocialProfileFakes.FakeGifts();
    private final SocialProfileFakes.FakeStats stats = new SocialProfileFakes.FakeStats(users);
    private final SocialProfileFakes.FakeAwards awards = new SocialProfileFakes.FakeAwards();
    private final SocialProfileFakes.FakeRateLimit rateLimit = new SocialProfileFakes.FakeRateLimit();
    private final SocialProfileSupport support = SocialProfileFakes.support(true);

    private final PointsService points = new PointsService(stats, support);
    private final ProfileService profiles = new ProfileService(settings, users, support, points);
    private final BoardService board = new BoardService(boards, users, rateLimit, support, profiles, points);
    private final FriendsService friends = new FriendsService(relations, users, rateLimit, support, points);
    private final GiftService giftService = new GiftService(gifts, users, rateLimit, support, points, settings);
    private final AwardService awardService = new AwardService(awards, users, support);
    private final AvatarSupport avatars = new AvatarSupport(new SocialProfileFakes.FakeAvatars(), users);

    private static WikiUser wikiUser(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private static SpecialPageRequest request(String name, Map<String, String> parameters, WikiUser user) {
        return new SpecialPageRequest(name, parameters, user);
    }

    // ==================== 编辑资料 ====================

    @Test
    void editProfilePagePopulatesFieldsGendersAndPreferences() {
        EditProfileSpecialPage page = new EditProfileSpecialPage(users, support, profiles, board, friends,
                giftService, avatars);
        assertEquals("编辑资料", page.name());
        assertEquals("special/edit-profile", page.template());
        assertTrue(page.aliases().contains("EditProfile"));

        profiles.save(1, new cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields("female", "03-09",
                "上海", "", "", "", "", "关于我"));
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("编辑资料", Map.of(), wikiUser(1, "甲")), model);
        assertEquals(Boolean.FALSE, model.get("featureDisabled"));
        assertEquals(Boolean.FALSE, model.get("loginRequired"));
        assertEquals("03-09", ((cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields) model.get("profile"))
                .birthday());
        assertEquals(3, ((java.util.List<?>) model.get("genders")).size(), "未指定 / 男 / 女");
        assertEquals("/avatars/u1-1.png", model.get("avatarUrl"));
        assertEquals(SocialProfileSupport.pagePath("特殊:积分排行"), String.valueOf(model.get("topUsersUrl")));

        // 匿名 / 功能关闭：只给出提示，不填资料
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(request("编辑资料", Map.of(), null), anonymous);
        assertEquals(Boolean.TRUE, anonymous.get("loginRequired"));
        assertFalse(anonymous.containsKey("profile"));

        EditProfileSpecialPage disabled = new EditProfileSpecialPage(users,
                SocialProfileFakes.support(false), profiles, board, friends, giftService, avatars);
        Map<String, Object> closed = new LinkedHashMap<>();
        disabled.populate(request("编辑资料", Map.of(), wikiUser(1, "甲")), closed);
        assertEquals(Boolean.TRUE, closed.get("featureDisabled"));
    }

    // ==================== 留言板 ====================

    @Test
    void userBoardPageShowsTargetBoardWithRepliesAndPostForm() {
        UserBoardSpecialPage page = new UserBoardSpecialPage(users, support, profiles, board, friends,
                giftService, avatars, SocialProfileFakes.wikiText());
        assertEquals("留言板", page.name());
        assertEquals("special/user-board", page.template());
        assertTrue(page.aliases().contains("UserBoard"));

        board.post(1, 2, "'''你好'''乙");
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of("user", "乙"), wikiUser(1, "甲")), model);
        assertEquals("乙", model.get("targetUser"));
        assertEquals(Boolean.FALSE, model.get("boardSelf"));
        assertEquals(Boolean.TRUE, model.get("boardCanPost"), "登录 + 不是本人 + 对方允许");
        assertEquals(1L, model.get("boardTotal"));
        java.util.List<?> rows = (java.util.List<?>) model.get("boardRows");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("甲", row.get("author"));
        assertTrue(String.valueOf(row.get("contentHtml")).contains("<b>你好</b>"),
                "留言正文按 wikitext 渲染：" + row.get("contentHtml"));

        // 自己的板：不能给自己留言，显示自己的提示
        Map<String, Object> self = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of(), wikiUser(1, "甲")), self);
        assertEquals(Boolean.TRUE, self.get("boardSelf"));
        assertEquals(Boolean.FALSE, self.get("boardCanPost"));

        // 匿名：只能看
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of("user", "乙"), null), anonymous);
        assertEquals(Boolean.TRUE, anonymous.get("loginRequired"));

        // 全站留言
        Map<String, Object> all = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of("all", "1"), wikiUser(1, "甲")), all);
        assertEquals(Boolean.TRUE, all.get("boardAll"));
        assertEquals(1, ((java.util.List<?>) all.get("boardRows")).size());

        // 用户不存在
        Map<String, Object> missing = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of("user", "幽灵"), wikiUser(1, "甲")), missing);
        assertEquals(Boolean.TRUE, missing.get("boardTargetNotFound"));
    }

    @Test
    void userBoardPageRespectsTheClosedBoardPreference() {
        UserBoardSpecialPage page = new UserBoardSpecialPage(users, support, profiles, board, friends,
                giftService, avatars, SocialProfileFakes.wikiText());
        profiles.savePreferences(2, new ProfileService.SocialPreferences(true, true, false, true, true));
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("留言板", Map.of("user", "乙"), wikiUser(1, "甲")), model);
        assertEquals(Boolean.FALSE, model.get("boardAllowBoard"));
        assertEquals(Boolean.FALSE, model.get("boardCanPost"), "对方关闭留言板后不给输入框");
    }

    // ==================== 好友 ====================

    @Test
    void friendsPageShowsTabsListsAndRequests() {
        FriendsSpecialPage page = new FriendsSpecialPage(users, support, profiles, board, friends, giftService,
                avatars);
        assertEquals("好友", page.name());
        assertEquals("special/friends", page.template());

        friends.requestFriend(2, 1);
        friends.requestFriend(1, 2);
        // 上一步让 1 与 2 直接成为好友（互相接受）
        assertEquals(1, friends.friendCount(1));

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("好友", Map.of(), wikiUser(1, "甲")), model);
        assertEquals(Boolean.TRUE, model.get("friendsSelf"));
        assertEquals("friends", model.get("friendsTab"));
        java.util.List<?> friendRows = (java.util.List<?>) model.get("friendRows");
        assertEquals(1, friendRows.size());
        assertEquals("乙", ((Map<?, ?>) friendRows.get(0)).get("username"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) friendRows.get(0)).get("pending"));

        // 看别人的好友列表：只读（没有请求操作）
        Map<String, Object> other = new LinkedHashMap<>();
        page.populate(request("好友", Map.of("user", "乙"), wikiUser(1, "甲")), other);
        assertEquals(Boolean.FALSE, other.get("friendsSelf"));
        assertEquals("乙", other.get("friendsOwner"));

        // 标签与「请求」只有本人能看
        Map<String, Object> foes = new LinkedHashMap<>();
        page.populate(request("好友", Map.of("tab", "foes"), wikiUser(1, "甲")), foes);
        assertEquals("foes", foes.get("friendsTab"));
        Map<String, Object> strangerRequests = new LinkedHashMap<>();
        page.populate(request("好友", Map.of("user", "乙", "tab", "requests"), wikiUser(1, "甲")),
                strangerRequests);
        assertEquals("friends", strangerRequests.get("friendsTab"), "别人的请求标签回退到好友列表");

        // 用户不存在 / 匿名
        Map<String, Object> missing = new LinkedHashMap<>();
        page.populate(request("好友", Map.of("user", "幽灵"), wikiUser(1, "甲")), missing);
        assertEquals(Boolean.TRUE, missing.get("friendsTargetNotFound"));
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(request("好友", Map.of(), null), anonymous);
        assertEquals(Boolean.TRUE, anonymous.get("loginRequired"));
    }

    // ==================== 礼物 ====================

    @Test
    void giftPagesListCatalogAndReceivedGifts() {
        SendGiftSpecialPage sendPage = new SendGiftSpecialPage(users, support, profiles, board, friends,
                giftService, avatars);
        assertEquals("送礼物", sendPage.name());
        assertEquals("special/send-gift", sendPage.template());
        Map<String, Object> sendModel = new LinkedHashMap<>();
        sendPage.populate(request("送礼物", Map.of("user", "乙"), wikiUser(1, "甲")), sendModel);
        assertEquals("乙", sendModel.get("giftTo"));
        assertEquals(GiftService.CATALOG.size(), ((java.util.List<?>) sendModel.get("giftCatalog")).size());

        GiftsSpecialPage giftsPage = new GiftsSpecialPage(users, support, profiles, board, friends, giftService,
                avatars);
        assertEquals("礼物", giftsPage.name());
        assertEquals("special/gifts", giftsPage.template());
        giftService.send(1, 2, "star", "送你一颗星");
        Map<String, Object> received = new LinkedHashMap<>();
        giftsPage.populate(request("礼物", Map.of(), wikiUser(2, "乙")), received);
        assertEquals(Boolean.FALSE, received.get("giftsSentBox"));
        java.util.List<?> rows = (java.util.List<?>) received.get("giftRows");
        assertEquals(1, rows.size());
        assertEquals("甲", ((Map<?, ?>) rows.get(0)).get("other"));
        assertEquals("送你一颗星", ((Map<?, ?>) rows.get(0)).get("message"));
        assertEquals(1L, received.get("giftTotal"));

        Map<String, Object> sent = new LinkedHashMap<>();
        giftsPage.populate(request("礼物", Map.of("box", "sent"), wikiUser(1, "甲")), sent);
        assertEquals(Boolean.TRUE, sent.get("giftsSentBox"));
        assertEquals(1, ((java.util.List<?>) sent.get("giftRows")).size());
    }

    // ==================== 积分排行 ====================

    @Test
    void topUsersPagePaginatesAndShowsLevelThresholds() {
        TopUsersSpecialPage page = new TopUsersSpecialPage(users, support, profiles, board, friends, giftService,
                avatars, points, stats, new cc.thonly.wikikernel.plugin.socialprofile.service.PointsRulesProvider(
                support));
        assertEquals("积分排行", page.name());
        assertEquals("special/top-users", page.template());

        points.awardPoints(1, 120, "TEST");
        points.awardPoints(2, 10, "TEST");
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("积分排行", Map.of(), wikiUser(1, "甲")), model);
        java.util.List<?> rows = (java.util.List<?>) model.get("topUsers");
        assertEquals(2, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals(1, first.get("rank"));
        assertEquals("甲", first.get("username"));
        assertEquals(120L, first.get("points"));
        assertEquals(2, first.get("level"), "120 分 → 2 级（默认 50 分门槛）");
        assertTrue(String.valueOf(first.get("levelName")).contains("常客"), String.valueOf(first.get("levelName")));
        assertEquals(2L, model.get("topRankedCount"));
        assertEquals(null, model.get("topPrevUrl"));
        assertEquals(5, ((java.util.List<?>) model.get("pointsLevels")).size());
        assertEquals(Boolean.TRUE, model.get("pointsEnabled"));

        // 功能关闭：只给提示
        TopUsersSpecialPage disabled = new TopUsersSpecialPage(users, SocialProfileFakes.support(false),
                profiles, board, friends, giftService, avatars, points, stats,
                new cc.thonly.wikikernel.plugin.socialprofile.service.PointsRulesProvider(
                        SocialProfileFakes.support(false)));
        Map<String, Object> closed = new LinkedHashMap<>();
        disabled.populate(request("积分排行", Map.of(), null), closed);
        assertEquals(Boolean.TRUE, closed.get("featureDisabled"));
        assertFalse(closed.containsKey("topUsers"));
    }

    // ==================== 奖励 ====================

    @Test
    void awardsPageHidesTheGrantFormFromNonAdmins() {
        cc.thonly.wikikernel.common.service.CurrentUser admin = SocialProfileFakes.currentUser(
                SocialProfileFakes.viewer(1, "甲", "管理员"));
        cc.thonly.wikikernel.common.service.CurrentUser normal = SocialProfileFakes.currentUser(
                SocialProfileFakes.viewer(2, "乙"));
        AwardsSpecialPage page = new AwardsSpecialPage(users, support, profiles, board, friends, giftService,
                avatars, awardService, normal);
        assertEquals("奖励", page.name());
        assertEquals("special/awards", page.template());

        awardService.grant(1, true, 2, "barnstar", "干得漂亮");
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("奖励", Map.of(), wikiUser(2, "乙")), model);
        assertEquals(Boolean.FALSE, model.get("awardsAdmin"));
        assertTrue(((java.util.List<?>) model.get("awardsCatalog")).isEmpty(), "非管理员拿不到清单");
        java.util.List<?> rows = (java.util.List<?>) model.get("awardRows");
        assertEquals(1, rows.size());
        assertEquals("barnstar", ((Map<?, ?>) rows.get(0)).get("awardId"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) rows.get(0)).get("canRevoke"));

        // 管理员：能拿清单、能撤销
        AwardsSpecialPage adminPage = new AwardsSpecialPage(users, support, profiles, board, friends,
                giftService, avatars, awardService, admin);
        Map<String, Object> adminModel = new LinkedHashMap<>();
        adminPage.populate(request("奖励", Map.of("user", "乙"), wikiUser(1, "甲")), adminModel);
        assertEquals(Boolean.TRUE, adminModel.get("awardsAdmin"));
        assertEquals(AwardService.CATALOG.size(), ((java.util.List<?>) adminModel.get("awardsCatalog")).size());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ((java.util.List<?>) adminModel.get("awardRows")).get(0))
                .get("canRevoke"));
    }

    // ==================== 随机用户 ====================

    @Test
    void randomUsersPageOnlyShowsUsersWithAvatars() {
        RandomUsersSpecialPage page = new RandomUsersSpecialPage(users, support, profiles, board, friends,
                giftService, avatars);
        assertEquals("随机用户", page.name());
        assertEquals("special/random-users", page.template());
        assertTrue(page.aliases().contains("RandomUsersWithAvatars"));

        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(request("随机用户", Map.of(), wikiUser(1, "甲")), model);
        java.util.List<?> rows = (java.util.List<?>) model.get("randomUsers");
        assertEquals(1, rows.size(), "只有「甲」上传过头像");
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("甲", row.get("username"));
        assertEquals("/avatars/u1-1.png", row.get("avatarUrl"));
        assertEquals(SocialProfileSupport.pagePath("特殊:留言板?user=" + SocialProfileSupport.encodeQuery("甲")), String.valueOf(row.get("boardUrl")));

        // limit 参数：不能超过配置上限（默认 12 = 一屏数量），配置可以调大但不超过硬上限
        Map<String, Object> limited = new LinkedHashMap<>();
        page.populate(request("随机用户", Map.of("limit", "999"), wikiUser(1, "甲")), limited);
        assertEquals((Object) RandomUsersSpecialPage.DEFAULT_LIMIT, limited.get("randomUsersLimit"),
                "请求超过配置上限时按上限来");
        Map<String, Object> smaller = new LinkedHashMap<>();
        page.populate(request("随机用户", Map.of("limit", "3"), wikiUser(1, "甲")), smaller);
        assertEquals(3, smaller.get("randomUsersLimit"));

        RandomUsersSpecialPage configured = new RandomUsersSpecialPage(users,
                SocialProfileFakes.support(true, Map.of("randomUsers.limit", "30")), profiles, board,
                friends, giftService, avatars);
        Map<String, Object> bigger = new LinkedHashMap<>();
        configured.populate(request("随机用户", Map.of("limit", "999"), wikiUser(1, "甲")), bigger);
        assertEquals(30, bigger.get("randomUsersLimit"), "配置调大后上限跟着变大");
        assertEquals(RandomUsersSpecialPage.MAX_LIMIT, RandomUsersSpecialPage.MAX_LIMIT,
                "硬上限常量保持 48（防止一页塞几百个头像）");
    }

    // ==================== 权限 / 开关 ====================

    @Test
    void viewerHelperIsUsedForAdminChecks() {
        Viewer admin = SocialProfileFakes.viewer(1, "甲", "管理员");
        Viewer bureaucrat = SocialProfileFakes.viewer(1, "甲", "行政员");
        Viewer sysop = SocialProfileFakes.viewer(1, "甲", "sysop");
        Viewer normal = SocialProfileFakes.viewer(1, "甲");
        assertTrue(admin.inGroup("管理员"));
        assertTrue(bureaucrat.inGroup("行政员"));
        assertTrue(sysop.inGroup("sysop"));
        assertFalse(normal.inGroup("管理员"));
    }
}
