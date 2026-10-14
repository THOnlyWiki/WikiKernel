package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.model.SocialProfileKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SocialProfile 的插件侧规则（不连数据库，全部走 {@link SocialProfileFakes} 的内存桥）：
 * 资料字段校验与保存、社交偏好、留言板（留言 / 回复 / 删除 / 限流 / 关闭留言板）、
 * 好友与仇敌（请求 / 接受 / 拒绝 / 解除 / 单向仇敌）、礼物（清单 / 发送 / 删除 / 附言截断）、
 * 积分与等级（给分规则 / 阈值表 / 浏览去重）、奖励（只能管理员 / 不重复授予）。
 */
class SocialProfileServicesTest {

    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "u1-1.png")
            .add(2, "乙", "")
            .add(3, "丙", "");
    private final SocialProfileFakes.FakeSettings settings = new SocialProfileFakes.FakeSettings();
    private final SocialProfileFakes.FakeBoards boards = new SocialProfileFakes.FakeBoards();
    private final SocialProfileFakes.FakeRelations relations = new SocialProfileFakes.FakeRelations();
    private final SocialProfileFakes.FakeGifts gifts = new SocialProfileFakes.FakeGifts();
    private final SocialProfileFakes.FakeStats stats = new SocialProfileFakes.FakeStats(users);
    private final SocialProfileFakes.FakeAwards awards = new SocialProfileFakes.FakeAwards();
    private final SocialProfileFakes.FakeRateLimit rateLimit = new SocialProfileFakes.FakeRateLimit();

    private SocialProfileSupport support(boolean enabled, Map<String, String> pluginSettings) {
        return SocialProfileFakes.support(enabled, pluginSettings);
    }

    private PointsService points(SocialProfileSupport support) {
        return new PointsService(stats, support);
    }

    private ProfileService profiles(SocialProfileSupport support) {
        PointsService points = points(support);
        return new ProfileService(settings, users, support, points);
    }

    private BoardService board(SocialProfileSupport support, ProfileService profiles) {
        return new BoardService(boards, users, rateLimit, support, profiles, points(support));
    }

    private FriendsService friends(SocialProfileSupport support) {
        return new FriendsService(relations, users, rateLimit, support, points(support));
    }

    private GiftService giftService(SocialProfileSupport support) {
        return new GiftService(gifts, users, rateLimit, support, points(support), settings);
    }

    // ==================== 资料字段 ====================

    @Test
    void profileFieldsSaveLoadAndNormalizeBirthday() {
        SocialProfileSupport support = support(true, Map.of());
        ProfileService profiles = profiles(support);

        // 空资料：全部字段为空，且不会写入性别以外的键
        assertTrue(profiles.of(1).isEmpty());

        ProfileFields saved = profiles.save(1, new ProfileFields("MALE", "3/9", " 上海 ", "杭州",
                "程序员", "写代码", "https://example.com", "'''你好'''"));
        assertEquals("male", saved.gender(), "性别归一化小写");
        assertEquals("03-09", saved.birthday(), "生日只存月-日并补零");
        assertEquals("上海", saved.location(), "去首尾空白");
        assertEquals("https://example.com", saved.website());

        ProfileFields loaded = profiles.of(1);
        assertEquals(saved, loaded, "写回后应当能原样读出");
        assertEquals("男", profiles.genderLabel(loaded.gender()));
        assertEquals("03-09", settings.get(1, SocialProfileKeys.BIRTHDAY, ""));

        // 完整日期（含年份）只保留月日
        assertEquals("12-31", ProfileFields.normalizeBirthday("1990-12-31"));
        assertEquals("", ProfileFields.normalizeBirthday("  "));
        assertNull(ProfileFields.normalizeBirthday("13-01"), "13 月不合法");
        assertNull(ProfileFields.normalizeBirthday("02-30"), "2 月 30 日不合法");
        assertEquals("02-29", ProfileFields.normalizeBirthday("02-29"), "闰日合法");
    }

    @Test
    void profileValidationRejectsBadInputWithMessageKeys() {
        ProfileService profiles = profiles(support(true, Map.of()));

        assertEquals(ProfileService.ProfileException.GENDER, assertThrows(
                ProfileService.ProfileException.class,
                () -> profiles.validate(new ProfileFields("alien", "", "", "", "", "", "", ""))).getMessage());
        assertEquals(ProfileService.ProfileException.BIRTHDAY, assertThrows(
                ProfileService.ProfileException.class,
                () -> profiles.validate(new ProfileFields("", "13-45", "", "", "", "", "", ""))).getMessage());
        assertEquals(ProfileService.ProfileException.WEBSITE, assertThrows(
                ProfileService.ProfileException.class,
                () -> profiles.validate(new ProfileFields("", "", "", "", "", "", "javascript:alert(1)", "")))
                .getMessage());
        assertEquals(ProfileService.ProfileException.LOCATION, assertThrows(
                ProfileService.ProfileException.class,
                () -> profiles.validate(new ProfileFields("", "", "x".repeat(101), "", "", "", "", "")))
                .getMessage());
        assertEquals(ProfileService.ProfileException.ABOUT, assertThrows(
                ProfileService.ProfileException.class,
                () -> profiles.validate(new ProfileFields("", "", "", "", "", "", "", "x".repeat(5001))))
                .getMessage());
        assertEquals(0, settings.writes, "校验失败不应写库");
    }

    @Test
    void socialPreferencesDefaultToShowAndAllow() {
        SocialProfileSupport support = support(true, Map.of());
        ProfileService profiles = profiles(support);

        assertEquals(ProfileService.SocialPreferences.DEFAULT, profiles.preferences(1));
        assertTrue(profiles.allowsBoard(1));

        ProfileService.SocialPreferences saved = profiles.savePreferences(1,
                new ProfileService.SocialPreferences(false, true, false, false, true));
        assertFalse(saved.showBirthday());
        assertFalse(saved.allowBoard());
        assertEquals(saved, profiles.preferences(1));
        assertFalse(profiles.allowsBoard(1), "关闭后不允许他人留言");
    }

    // ==================== 留言板 ====================

    @Test
    void boardPostReplyAndSoftDeleteFollowTheRules() {
        SocialProfileSupport support = support(true, Map.of());
        ProfileService profiles = profiles(support);
        BoardService board = board(support, profiles);

        WikiUserBoard post = board.post(1, 2, "  第一条留言  ");
        assertEquals("第一条留言", post.getContent(), "去首尾空白");
        assertEquals(2L, post.getToUserId().longValue());
        assertEquals(1L, post.getFromUserId().longValue());
        assertNull(post.getParentId());
        assertFalse(Boolean.TRUE.equals(post.getDeleted()));
        assertEquals(1, board.count(2));

        WikiUserBoard reply = board.reply(3, post.getId(), "回复一下");
        assertEquals(post.getId(), reply.getParentId());
        assertEquals(2L, reply.getToUserId().longValue(), "回复挂在板主名下");
        assertEquals(1, board.replies(post.getId(), 10).size());

        // 删除：板主可以删；软删除后仍占位、重复删返回 false
        assertTrue(board.delete(2, post.getId(), false));
        assertTrue(Boolean.TRUE.equals(board.find(post.getId()).orElseThrow().getDeleted()));
        assertEquals(2, board.count(2), "两条留言（顶楼 + 回复）都算在板主 2 名下");
        assertFalse(board.delete(2, post.getId(), false), "已删除的再删返回 false");

        // 作者本人可以删；管理员可以删；既不是板主也不是作者的无关用户不行
        assertTrue(board.delete(3, reply.getId(), false), "作者删自己的回复");
        WikiUserBoard another = board.post(2, 3, "乙给丙的留言");
        assertEquals(BoardService.BoardException.FORBIDDEN, assertThrows(
                BoardService.BoardException.class, () -> board.delete(1, another.getId(), false)).getMessage(),
                "甲既不是板主（丙）也不是作者（乙），不能删");
        assertTrue(board.delete(1, another.getId(), true), "管理员可以删别人的留言");
    }

    @Test
    void boardRejectsSelfClosedEmptyTooLongAndUnknownTargets() {
        SocialProfileSupport support = support(true, Map.of());
        ProfileService profiles = profiles(support);
        BoardService board = board(support, profiles);

        assertEquals(BoardService.BoardException.LOGIN_REQUIRED, assertThrows(
                BoardService.BoardException.class, () -> board.post(0, 2, "x")).getMessage());
        assertEquals(BoardService.BoardException.USER_NOT_FOUND, assertThrows(
                BoardService.BoardException.class, () -> board.post(1, 99, "x")).getMessage());
        assertEquals(BoardService.BoardException.SELF_NOT_ALLOWED, assertThrows(
                BoardService.BoardException.class, () -> board.post(1, 1, "x")).getMessage());
        assertEquals(BoardService.BoardException.EMPTY, assertThrows(
                BoardService.BoardException.class, () -> board.post(1, 2, "   ")).getMessage());
        assertEquals(BoardService.BoardException.TOO_LONG, assertThrows(
                BoardService.BoardException.class,
                () -> board.post(1, 2, "x".repeat(BoardService.DEFAULT_MAX_LENGTH + 1))).getMessage());
        assertEquals(BoardService.BoardException.PARENT_NOT_FOUND, assertThrows(
                BoardService.BoardException.class, () -> board.reply(1, 999, "x")).getMessage());

        // 板主关闭留言板后不能再留言（回复同一条规则）
        WikiUserBoard post = board.post(1, 2, "先留一条");
        profiles.savePreferences(2, new ProfileService.SocialPreferences(true, true, false, true, true));
        assertEquals(BoardService.BoardException.BOARD_CLOSED, assertThrows(
                BoardService.BoardException.class, () -> board.post(1, 2, "再留一条")).getMessage());
        assertEquals(BoardService.BoardException.BOARD_CLOSED, assertThrows(
                BoardService.BoardException.class, () -> board.reply(3, post.getId(), "回复")).getMessage());

        // 页面大小 / 长度上限可配置（插件配置短键）
        BoardService configured = board(support(true, Map.of("board.pageSize", "3", "board.pageLimit", "7",
                "board.maxLength", "10")), profiles);
        assertEquals(3, configured.profileLimit());
        assertEquals(7, configured.pageLimit());
        assertEquals(10, configured.maxLength());
    }

    @Test
    void boardRateLimitBlocksFurtherPosts() {
        SocialProfileSupport support = support(true, Map.of());
        BoardService board = board(support, profiles(support));
        rateLimit.limitedKeys.add(BoardService.RATE_LIMIT_CATEGORY + "|1");

        BoardService.BoardException exception = assertThrows(BoardService.BoardException.class,
                () -> board.post(1, 2, "触发限流"));
        assertEquals(BoardService.BoardException.RATE_LIMITED, exception.getMessage());
        assertTrue(exception.retryAfterSeconds() > 0);
        assertEquals(0, boards.rows.size(), "被限流时不应写库");
    }

    // ==================== 好友 / 仇敌 ====================

    @Test
    void friendRequestAcceptCreatesBothDirections() {
        SocialProfileSupport support = support(true, Map.of());
        FriendsService friends = friends(support);

        WikiUserRelation request = friends.requestFriend(1, 2);
        assertEquals(WikiUserRelation.STATUS_PENDING, request.getStatus());
        assertEquals("PENDING", friends.relationState(1, 2));
        assertEquals("INCOMING", friends.relationState(2, 1));
        assertEquals(1, friends.pendingCount(2));

        // 幂等：再发一次返回同一行
        assertEquals(request.getId(), friends.requestFriend(1, 2).getId());

        // 只有收件人能接受
        assertEquals(FriendsService.FriendException.REQUEST_NOT_FOUND, assertThrows(
                FriendsService.FriendException.class, () -> friends.accept(3, request.getId())).getMessage());
        WikiUserRelation accepted = friends.accept(2, request.getId());
        assertEquals(WikiUserRelation.STATUS_ACCEPTED, accepted.getStatus());
        assertEquals("FRIEND", friends.relationState(1, 2));
        assertEquals("FRIEND", friends.relationState(2, 1));
        assertEquals(1, friends.friendCount(1), "双方各一行 ACCEPTED");
        assertEquals(1, friends.friendCount(2));
        assertEquals(0, friends.pendingCount(2));
        assertEquals(1L, friends.friendCounts(List.of(1L, 2L)).get(1L));

        // 双方各得一次加好友积分（默认 10 分）
        assertTrue(stats.byUser(1).orElseThrow().getPoints() >= 10);
        assertTrue(stats.byUser(2).orElseThrow().getPoints() >= 10);
    }

    @Test
    void friendRequestAcceptWhenTheOtherSideAlreadyAsked() {
        SocialProfileSupport support = support(true, Map.of());
        FriendsService friends = friends(support);

        friends.requestFriend(2, 1);
        // 甲反过来发请求 → 直接互相接受，不产生两条 PENDING
        WikiUserRelation result = friends.requestFriend(1, 2);
        assertEquals(WikiUserRelation.STATUS_ACCEPTED, result.getStatus());
        assertTrue(relations.find(1, 2, WikiUserRelation.TYPE_FRIEND).orElseThrow().getStatus()
                .equals(WikiUserRelation.STATUS_ACCEPTED));
        assertTrue(relations.find(2, 1, WikiUserRelation.TYPE_FRIEND).orElseThrow().getStatus()
                .equals(WikiUserRelation.STATUS_ACCEPTED));
    }

    @Test
    void friendRejectRemoveAndFoeRules() {
        SocialProfileSupport support = support(true, Map.of());
        FriendsService friends = friends(support);

        WikiUserRelation request = friends.requestFriend(1, 2);
        assertTrue(friends.reject(2, request.getId()));
        assertTrue(relations.rows.isEmpty(), "拒绝 = 删除请求行");
        assertEquals("NONE", friends.relationState(1, 2));

        // 已经是好友时再加 → 报「已经是好友」
        friends.requestFriend(1, 2);
        friends.accept(2, relations.find(1, 2, WikiUserRelation.TYPE_FRIEND).orElseThrow().getId());
        assertEquals(FriendsService.FriendException.ALREADY_FRIEND, assertThrows(
                FriendsService.FriendException.class, () -> friends.requestFriend(1, 2)).getMessage());

        // 解除好友：两个方向都删
        assertTrue(friends.removeFriend(1, 2));
        assertTrue(relations.rows.isEmpty());
        assertEquals(FriendsService.FriendException.NOT_FRIEND, assertThrows(
                FriendsService.FriendException.class, () -> friends.removeFriend(1, 2)).getMessage());

        // 仇敌：单向、立即生效、幂等、可以移出
        WikiUserRelation foe = friends.addFoe(1, 3);
        assertEquals(WikiUserRelation.STATUS_ACCEPTED, foe.getStatus());
        assertEquals("FOE", friends.relationState(1, 3));
        assertEquals("NONE", friends.relationState(3, 1), "对方看不到这层关系");
        assertTrue(friends.isFoeOf(1, 3));
        assertFalse(friends.isFoeOf(3, 1));
        assertEquals(foe.getId(), friends.addFoe(1, 3).getId(), "幂等");
        assertTrue(friends.removeFoe(1, 3));
        assertEquals(FriendsService.FriendException.NOT_FOE, assertThrows(
                FriendsService.FriendException.class, () -> friends.removeFoe(1, 3)).getMessage());

        // 非法目标
        assertEquals(FriendsService.FriendException.SELF_NOT_ALLOWED, assertThrows(
                FriendsService.FriendException.class, () -> friends.requestFriend(1, 1)).getMessage());
        assertEquals(FriendsService.FriendException.USER_NOT_FOUND, assertThrows(
                FriendsService.FriendException.class, () -> friends.requestFriend(1, 99)).getMessage());
        assertEquals(FriendsService.FriendException.LOGIN_REQUIRED, assertThrows(
                FriendsService.FriendException.class, () -> friends.requestFriend(0, 2)).getMessage());
    }

    // ==================== 礼物 ====================

    @Test
    void giftCatalogSendAndDelete() {
        SocialProfileSupport support = support(true, Map.of());
        GiftService gifts = giftService(support);

        assertEquals(GiftService.CATALOG.size(), gifts.catalog().size());
        assertTrue(gifts.isKnown("star"));
        assertFalse(gifts.isKnown("diamond"), "清单外的礼物 id 不合法");
        assertTrue(gifts.catalog().stream().allMatch(row -> {
            String name = String.valueOf(row.get("name"));
            return !name.isEmpty() && !name.contains("socialProfile");
        }), "礼物名走消息键（真实文案来自 i18n，不是键本身）");

        WikiUserGift gift = gifts.send(1, 2, "star", "  谢谢  ");
        assertEquals("star", gift.getGiftId());
        assertEquals("谢谢", gift.getMessage());
        assertEquals(1, gifts.received(2, 10).size());
        assertEquals(1, gifts.sent(1, 10).size());
        assertEquals(1, gifts.receivedCount(2));
        assertEquals(1L, gifts.receivedCounts(List.of(2L)).get(2L));
        assertEquals(Map.of("star", 1L), gifts.receivedByType(2));
        assertTrue(stats.byUser(1).orElseThrow().getPoints() >= 3, "送礼人得积分");

        // 附言超长截断（不会因为附言过长而失败）
        WikiUserGift longMessage = gifts.send(1, 2, "heart", "x".repeat(GiftService.MESSAGE_LIMIT + 50));
        assertEquals(GiftService.MESSAGE_LIMIT, longMessage.getMessage().length());

        // 删除：收礼人可以删；无关用户不行
        assertEquals(GiftService.GiftException.FORBIDDEN, assertThrows(
                GiftService.GiftException.class, () -> gifts.delete(3, gift.getId())).getMessage());
        assertTrue(gifts.delete(2, gift.getId()));
        assertEquals(GiftService.GiftException.NOT_FOUND, assertThrows(
                GiftService.GiftException.class, () -> gifts.delete(2, gift.getId())).getMessage());

        // 非法输入
        assertEquals(GiftService.GiftException.SELF_NOT_ALLOWED, assertThrows(
                GiftService.GiftException.class, () -> gifts.send(1, 1, "star", "")).getMessage());
        assertEquals(GiftService.GiftException.UNKNOWN_GIFT, assertThrows(
                GiftService.GiftException.class, () -> gifts.send(1, 2, "diamond", "")).getMessage());
        assertEquals(GiftService.GiftException.USER_NOT_FOUND, assertThrows(
                GiftService.GiftException.class, () -> gifts.send(1, 99, "star", "")).getMessage());
        assertEquals(GiftService.GiftException.LOGIN_REQUIRED, assertThrows(
                GiftService.GiftException.class, () -> gifts.send(0, 2, "star", "")).getMessage());
    }

    @Test
    void giftUnseenCountTracksTheSeenWatermark() {
        SocialProfileSupport support = support(true, Map.of());
        GiftService gifts = giftService(support);

        gifts.send(1, 3, "cookie", "");
        assertEquals(1, gifts.unseenCount(3), "没看过时全部算新");
        gifts.markSeen(3);
        assertEquals(0, gifts.unseenCount(3));
        assertNotNull(gifts.lastSeenAt(3));
        assertEquals(0, gifts.unseenCount(0), "匿名没有礼物");
    }

    // ==================== 积分 / 等级 ====================

    @Test
    void pointsRulesAreConfigurableAndLevelsComeFromTheThresholdTable() {
        SocialProfileSupport defaults = support(true, Map.of());
        PointsRules rules = defaults.pointsRules();
        assertTrue(rules.enabled());
        assertEquals(PointsRules.DEFAULT_PAGE_VIEW, rules.pointsFor(PointsRules.Action.PAGE_VIEW));
        assertEquals(PointsRules.DEFAULT_BOARD_POST, rules.pointsFor(PointsRules.Action.BOARD_POST));
        assertEquals(PointsRules.DEFAULT_FRIEND, rules.pointsFor(PointsRules.Action.FRIEND));
        assertEquals(PointsRules.DEFAULT_GIFT, rules.pointsFor(PointsRules.Action.GIFT));
        assertEquals(PointsRules.DEFAULT_PROFILE_EDIT, rules.pointsFor(PointsRules.Action.PROFILE_EDIT));
        assertEquals(PointsRules.DEFAULT_VIEW_COOLDOWN_SECONDS, rules.viewCooldownSeconds());

        // 默认阈值表：1/2/3/4/5 级 = 0/50/200/500/1000
        assertEquals(5, rules.levels().size());
        assertEquals(1, rules.levelFor(0));
        assertEquals(1, rules.levelFor(49));
        assertEquals(2, rules.levelFor(50));
        assertEquals(2, rules.levelFor(199), "199 分还没到 3 级的 200 分门槛");
        assertEquals(3, rules.levelFor(200));
        assertEquals(5, rules.levelFor(10_000));
        assertEquals(200, rules.minPointsFor(3));
        assertEquals(0, rules.minPointsFor(1));

        // 配置覆盖：分值 / 冷却 / 阈值表（站点设置键）
        SocialProfileSupport customized = SocialProfileFakes.supportWithSettings(Map.of(
                "socialProfile.points.boardPost", "12",
                "socialProfile.points.pageView", "0",
                "socialProfile.points.viewCooldownSeconds", "60",
                "socialProfile.points.levels", "1:0;2:10;9:100"));
        PointsRules custom = customized.pointsRules();
        assertEquals(12, custom.pointsFor(PointsRules.Action.BOARD_POST));
        assertEquals(0, custom.pointsFor(PointsRules.Action.PAGE_VIEW), "配成 0 = 该动作不给分");
        assertEquals(60, custom.viewCooldownSeconds());
        assertEquals(9, custom.levelFor(100));
        assertEquals(9, custom.levelFor(1000), "超过最高阈值仍是最高等级");

        // 非法阈值表 → 回退默认（默认表共 5 级，等级与门槛都按默认算）
        PointsRules broken = SocialProfileFakes.supportWithSettings(Map.of(
                "socialProfile.points.levels", "abc;def")).pointsRules();
        assertEquals(5, broken.levels().size(), "非法配置回退默认阈值表");
        assertEquals(2, broken.levelFor(50), "回退后仍按默认门槛换算等级");

        // 功能开关（features.social-profile）不影响给分总开关：后者单独配
        assertTrue(SocialProfileFakes.support(false).pointsRules().enabled(),
                "关掉「用户交互」不等于关掉给分（两个开关互相独立）");
        assertFalse(SocialProfileFakes.supportWithSettings(Map.of(
                "socialProfile.points.enabled", "false")).pointsRules().enabled());
        assertFalse(SocialProfileFakes.support(true, Map.of("points.enabled", "false"))
                .pointsRules().enabled(), "插件配置短键优先于站点设置");
    }

    @Test
    void pointsAreAwardedForSocialActionsAndPageViews() {
        SocialProfileSupport support = support(true, Map.of());
        PointsService points = points(support);
        ProfileService profiles = new ProfileService(settings, users, support, points);
        BoardService board = new BoardService(boards, users, rateLimit, support, profiles, points);

        assertEquals(0, points.pointsOf(1));
        assertEquals(1, points.levelOf(1));

        board.post(1, 2, "留言");
        long afterPost = points.pointsOf(1);
        assertEquals(PointsRules.DEFAULT_BOARD_POST, afterPost);

        profiles.save(1, new ProfileFields("female", "", "", "", "", "", "", ""));
        assertEquals(afterPost + PointsRules.DEFAULT_PROFILE_EDIT, points.pointsOf(1));

        // 页面浏览：冷却期内只计一次，冷却期外再计
        assertTrue(points.awardPageView(1, 100, false), "第一次浏览给分");
        long afterView = points.pointsOf(1);
        assertFalse(points.awardPageView(1, 100, false), "冷却期内重复浏览不给分");
        assertEquals(afterView, points.pointsOf(1));
        assertFalse(points.awardPageView(1, 100, true), "机器人不给分");
        assertFalse(points.awardPageView(0, 100, false), "匿名不给分");

        // 冷却时长可配：配成 0 = 每次都计
        PointsService noCooldown = new PointsService(stats, SocialProfileFakes.supportWithSettings(
                Map.of("socialProfile.points.viewCooldownSeconds", "0")));
        assertTrue(noCooldown.awardPageView(2, 100, false));
        assertTrue(noCooldown.awardPageView(2, 100, false));

        // 等级随积分提升（阈值表默认 2 级 = 50 分）
        stats.setPoints(3, 49, 1);
        PointsService leveler = new PointsService(stats, support);
        assertEquals(1, leveler.levelOf(3));
        leveler.awardPoints(3, 1, "TEST");
        assertEquals(50, leveler.pointsOf(3));
        assertEquals(2, leveler.levelOf(3), "跨过阈值后等级自动提升并落库");
        assertEquals(2, stats.byUser(3).orElseThrow().getLevel());

        // 手工重算等级
        stats.setPoints(3, 5000, 1);
        assertEquals(5, leveler.refreshLevel(3).getLevel());
        assertTrue(leveler.recentEvents().size() > 0, "最近给分事件用于诊断");
    }

    @Test
    void topUsersRankingIsSortedAndPaginated() {
        SocialProfileSupport support = support(true, Map.of());
        PointsService points = points(support);
        stats.setPoints(1, 30, 1);
        stats.setPoints(2, 120, 1);
        stats.setPoints(3, 5, 1);
        points.refreshLevel(2);

        var top = points.top(0, 2);
        assertEquals(2, top.size());
        assertEquals(2L, top.get(0).userId());
        assertEquals("乙", top.get(0).username());
        assertEquals(120, top.get(0).points());
        assertEquals(2, top.get(0).level());
        assertEquals(1L, top.get(1).userId());
        assertEquals(3, points.rankedCount());

        var page2 = points.top(2, 2);
        assertEquals(1, page2.size());
        assertEquals(3L, page2.get(0).userId());
    }

    // ==================== 奖励 ====================

    @Test
    void awardsOnlyAdminsCanGrantAndTheSameAwardIsNotDuplicated() {
        SocialProfileSupport support = support(true, Map.of());
        AwardService awards = new AwardService(this.awards, users, support);

        assertEquals(AwardService.CATALOG.size(), awards.catalog().size());
        assertTrue(awards.catalog().stream().allMatch(row -> {
            String name = String.valueOf(row.get("name"));
            return !name.isEmpty() && !name.contains("socialProfile");
        }), "奖励名走消息键（真实文案来自 i18n，不是键本身）");

        // 非管理员 / 未登录
        assertEquals(AwardService.AwardException.FORBIDDEN, assertThrows(
                AwardService.AwardException.class, () -> awards.grant(1, false, 2, "barnstar", "")).getMessage());
        assertEquals(AwardService.AwardException.LOGIN_REQUIRED, assertThrows(
                AwardService.AwardException.class, () -> awards.grant(0, true, 2, "barnstar", "")).getMessage());

        // 管理员授予 + 幂等
        WikiUserAward granted = awards.grant(1, true, 2, "barnstar", "  干得漂亮  ");
        assertEquals("barnstar", granted.getAwardId());
        assertEquals("干得漂亮", granted.getComment());
        assertEquals(granted.getId(), awards.grant(1, true, 2, "barnstar", "再来一次").getId());
        assertEquals(List.of("barnstar"), awards.awardIdsOf(2));
        assertEquals(1L, awards.counts(List.of(2L)).get(2L));

        // 撤销
        assertTrue(awards.revoke(1, true, granted.getId()));
        assertTrue(awards.awardIdsOf(2).isEmpty());
        assertEquals(AwardService.AwardException.NOT_FOUND, assertThrows(
                AwardService.AwardException.class, () -> awards.revoke(1, true, granted.getId())).getMessage());

        // 未知奖励 / 用户不存在
        assertEquals(AwardService.AwardException.UNKNOWN_AWARD, assertThrows(
                AwardService.AwardException.class, () -> awards.grant(1, true, 2, "nobel", "")).getMessage());
        assertEquals(AwardService.AwardException.USER_NOT_FOUND, assertThrows(
                AwardService.AwardException.class, () -> awards.grant(1, true, 99, "barnstar", "")).getMessage());
    }

    // ==================== 规则对象 ====================

    @Test
    void pointsRulesRecordBehavesAsADataHolder() {
        PointsRules rules = new PointsRules(true, 1, 2, 3, 4, 5, 6, "1:0;2:10");
        assertEquals(1, rules.pointsFor(PointsRules.Action.PAGE_VIEW));
        assertEquals(2, rules.pointsFor(PointsRules.Action.BOARD_POST));
        assertEquals(3, rules.pointsFor(PointsRules.Action.FRIEND));
        assertEquals(4, rules.pointsFor(PointsRules.Action.GIFT));
        assertEquals(5, rules.pointsFor(PointsRules.Action.PROFILE_EDIT));
        assertEquals(0, PointsRules.DISABLED.pointsFor(PointsRules.Action.PAGE_VIEW));
        assertEquals(2, rules.levelFor(10));
        assertFalse(PointsRules.DISABLED.enabled());
        assertNotNull(Viewer.of(1, "甲", java.util.Set.of()));
    }
}
