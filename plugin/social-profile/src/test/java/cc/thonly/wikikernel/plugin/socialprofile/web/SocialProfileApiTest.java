package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SocialProfile 的 API 语义（与私信 / 头像接口同一套闸门）：
 * 功能关闭 403、未登录 401、被封禁 403、非法输入 400（本地化消息键）、
 * 授权（管理员才能授予奖励、板主 / 作者 / 管理员才能删留言、只有当事人能删礼物）、
 * 以及限流（{@code social-profile-board} / {@code -friend} / {@code -gift}）。
 */
class SocialProfileApiTest {

    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "")
            .add(2, "乙", "")
            .add(3, "丙", "");
    private final SocialProfileFakes.FakeBoards boards = new SocialProfileFakes.FakeBoards();
    private final SocialProfileFakes.FakeRelations relations = new SocialProfileFakes.FakeRelations();
    private final SocialProfileFakes.FakeGifts gifts = new SocialProfileFakes.FakeGifts();
    private final SocialProfileFakes.FakeStats stats = new SocialProfileFakes.FakeStats(users);
    private final SocialProfileFakes.FakeAwards awards = new SocialProfileFakes.FakeAwards();
    private final SocialProfileFakes.FakeSettings settings = new SocialProfileFakes.FakeSettings();
    private final SocialProfileFakes.FakeRateLimit rateLimit = new SocialProfileFakes.FakeRateLimit();

    private final SocialProfileSupport support = SocialProfileFakes.support(true);
    private final PointsService points = new PointsService(stats, support);
    private final ProfileService profiles = new ProfileService(settings, users, support, points);
    private final BoardService board = new BoardService(boards, users, rateLimit, support, profiles, points);
    private final FriendsService friends = new FriendsService(relations, users, rateLimit, support, points);
    private final GiftService giftService = new GiftService(gifts, users, rateLimit, support, points, settings);
    private final AwardService awardService = new AwardService(awards, users, support);

    // ==================== 资料 ====================

    @Test
    void profileApiValidatesAndSavesFields() {
        Map<String, Object> saved = profileController(SocialProfileFakes.viewer(1, "甲"), true)
                .saveProfile("male", "3/9", "上海", "", "", "", "https://a.example", "'''你好'''");
        assertEquals(Boolean.TRUE, saved.get("success"));
        assertEquals("资料已保存。", saved.get("message"));
        assertEquals("03-09", profiles.of(1).birthday());
        assertEquals("上海", profiles.of(1).location());
        assertEquals("https://a.example", profiles.of(1).website());
        assertEquals("'''你好'''", profiles.of(1).about());

        // 校验失败 → success=false + 本地化文案（消息键 socialProfile.error.*）
        Map<String, Object> badGender = profileController(SocialProfileFakes.viewer(1, "甲"), true)
                .saveProfile("alien", "", "", "", "", "", "", "");
        assertEquals(Boolean.FALSE, badGender.get("success"));
        assertEquals("性别取值不合法。", badGender.get("message"));

        Map<String, Object> badBirthday = profileController(SocialProfileFakes.viewer(1, "甲"), true)
                .saveProfile("", "13-45", "", "", "", "", "", "");
        assertEquals("生日格式不正确：请用「月-日」（如 03-09）。", badBirthday.get("message"));

        Map<String, Object> badWebsite = profileController(SocialProfileFakes.viewer(1, "甲"), true)
                .saveProfile("", "", "", "", "", "", "javascript:alert(1)", "");
        assertTrue(String.valueOf(badWebsite.get("message")).contains("http"),
                String.valueOf(badWebsite.get("message")));
    }


    @Test
    void profileApiRejectsAnonymousDisabledAndBlocked() {
        Map<String, Object> anonymous = profileController(Viewer.ANONYMOUS, true)
                .saveProfile("", "", "", "", "", "", "", "");
        assertEquals(Boolean.FALSE, anonymous.get("success"));
        assertEquals("请先登录。", anonymous.get("message"));

        Map<String, Object> disabled = profileController(SocialProfileFakes.viewer(1, "甲"), false)
                .saveProfile("", "", "", "", "", "", "", "");
        assertEquals("用户交互功能未启用。", disabled.get("message"));

        // 被封禁：所有需要登录的接口都先给封禁说明（用留言板接口验证，它走同一套闸门）
        Map<String, Object> blocked = new SocialProfileBoardApiController(board, users,
                SocialProfileFakes.access("你已被封禁。"),
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(1, "甲")),
                SocialProfileFakes.support(true)).post("乙", "x", null);
        assertEquals("你已被封禁。", blocked.get("message"));
    }

    @Test
    void preferencesApiSavesCheckboxes() {
        SocialProfileApiController controller = profileController(SocialProfileFakes.viewer(1, "甲"), true);
        Map<String, Object> result = controller.savePreferences("1", "0", null, "yes", "on");
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("偏好已保存。", result.get("message"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(Boolean.TRUE, data.get("showBirthday"));
        assertEquals(Boolean.FALSE, data.get("showLocation"));
        assertEquals(Boolean.FALSE, data.get("allowBoard"), "没提交 = 未勾选");
        assertEquals(Boolean.TRUE, data.get("showGifts"));
        assertEquals(Boolean.TRUE, data.get("showAwards"));
        assertFalse(profiles.allowsBoard(1));
    }

    // ==================== 留言板 ====================

    @Test
    void boardApiPostsRepliesAndEnforcesDeletePermission() {
        assertEquals("请先登录。", boardController(Viewer.ANONYMOUS, true).post("乙", "x", null).get("message"));
        assertEquals("用户交互功能未启用。",
                boardController(SocialProfileFakes.viewer(1, "甲"), false).post("乙", "x", null).get("message"));

        SocialProfileBoardApiController controller = boardController(SocialProfileFakes.viewer(1, "甲"), true);
        assertEquals("用户不存在。", controller.post("幽灵", "x", null).get("message"));
        assertEquals("不能给自己的留言板留言。", controller.post("甲", "x", null).get("message"));
        assertEquals("留言内容不能为空。", controller.post("乙", "   ", null).get("message"));

        Map<String, Object> posted = controller.post("乙", "你好", null);
        assertEquals(Boolean.TRUE, posted.get("success"));
        assertEquals("留言已发布。", posted.get("message"));
        long id = ((Number) ((Map<?, ?>) ((Map<?, ?>) posted.get("data")).get("message")).get("id")).longValue();

        // 回复（parent = 父留言 id）
        assertEquals(Boolean.TRUE, controller.post("乙", "回复", String.valueOf(id)).get("success"));
        assertEquals(1, board.replies(id, 10).size());

        // 无关用户（丙）不能删；作者（甲）可以删
        Map<String, Object> forbidden = boardController(SocialProfileFakes.viewer(3, "丙"), true).delete(id);
        assertEquals(Boolean.FALSE, forbidden.get("success"));
        assertEquals("你没有权限删除这条留言。", forbidden.get("message"));
        assertFalse(Boolean.TRUE.equals(board.find(id).orElseThrow().getDeleted()));

        assertEquals("留言已删除。", boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .delete(id).get("message"));
        assertTrue(Boolean.TRUE.equals(board.find(id).orElseThrow().getDeleted()));

        // 已删除的再删：服务层返回 false（幂等），接口给「已经删过」的提示
        Map<String, Object> deletedAgain = boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .delete(id);
        assertEquals(Boolean.FALSE, deletedAgain.get("success"));
        assertEquals("（这条留言已被删除）", deletedAgain.get("message"));
        // 不存在
        assertEquals("留言不存在。", boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .delete(9999).get("message"));
    }

    @Test
    void boardApiAllowsAdminsToDeleteOthersMessages() {
        Map<String, Object> posted = boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .post("乙", "别人的留言", null);
        long id = ((Number) ((Map<?, ?>) ((Map<?, ?>) posted.get("data")).get("message")).get("id")).longValue();

        // 管理员（按用户组判定）可以删别人的留言：丙既不是板主（乙）也不是作者（甲）
        SocialProfileBoardApiController admin = boardController(
                SocialProfileFakes.viewer(3, "丙", "管理员"), true);
        assertEquals("留言已删除。", admin.delete(id).get("message"));
        assertTrue(Boolean.TRUE.equals(board.find(id).orElseThrow().getDeleted()));

        // 同一个操作对普通用户（没有管理员组）是 403
        Map<String, Object> secondPosted = boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .post("乙", "再一条", null);
        long secondId = ((Number) ((Map<?, ?>) ((Map<?, ?>) secondPosted.get("data")).get("message")).get("id"))
                .longValue();
        assertEquals("你没有权限删除这条留言。", boardController(SocialProfileFakes.viewer(3, "丙"), true)
                .delete(secondId).get("message"));
    }

    @Test
    void boardApiReportsRateLimitAndClosedBoard() {
        rateLimit.limitedKeys.add(BoardService.RATE_LIMIT_CATEGORY + "|1");
        Map<String, Object> limited = boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .post("乙", "x", null);
        assertEquals(Boolean.FALSE, limited.get("success"));
        assertTrue(String.valueOf(limited.get("message")).contains("操作过于频繁"),
                String.valueOf(limited.get("message")));

        rateLimit.limitedKeys.clear();
        profiles.savePreferences(2, new ProfileService.SocialPreferences(true, true, false, true, true));
        assertEquals("对方已关闭留言板。", boardController(SocialProfileFakes.viewer(1, "甲"), true)
                .post("乙", "x", null).get("message"));
    }

    // ==================== 好友 / 仇敌 ====================

    @Test
    void friendApiCoversRequestAcceptRejectRemoveAndFoes() {
        assertEquals("请先登录。", friendController(Viewer.ANONYMOUS, true).requestFriend("乙").get("message"));

        SocialProfileFriendApiController controller = friendController(SocialProfileFakes.viewer(1, "甲"), true);
        assertEquals("用户不存在。", controller.requestFriend("幽灵").get("message"));

        Map<String, Object> requested = controller.requestFriend("乙");
        assertEquals(Boolean.TRUE, requested.get("success"));
        assertEquals("好友请求已发送。", requested.get("message"));
        long relationId = ((Number) ((Map<?, ?>) requested.get("data")).get("id")).longValue();

        // 不是收件人不能接受
        assertEquals("好友请求不存在，或不是发给你的。", friendController(SocialProfileFakes.viewer(3, "丙"), true)
                .accept(relationId).get("message"));

        assertEquals("已接受好友请求。", friendController(SocialProfileFakes.viewer(2, "乙"), true)
                .accept(relationId).get("message"));
        assertEquals(1, friends.friendCount(1));

        assertEquals("你们已经是好友了。", controller.requestFriend("乙").get("message"));

        // 拒绝：重新发起一条请求再拒绝
        friends.removeFriend(1, 2);
        Map<String, Object> again = controller.requestFriend("乙");
        long secondId = ((Number) ((Map<?, ?>) again.get("data")).get("id")).longValue();
        assertEquals("已拒绝好友请求。", friendController(SocialProfileFakes.viewer(2, "乙"), true)
                .reject(secondId).get("message"));
        assertEquals("你们还不是好友。", controller.remove("乙").get("message"));

        // 解除好友（双向删除）
        Map<String, Object> third = controller.requestFriend("乙");
        friendController(SocialProfileFakes.viewer(2, "乙"), true)
                .accept(((Number) ((Map<?, ?>) third.get("data")).get("id")).longValue());
        assertEquals("已解除好友关系。", controller.remove("乙").get("message"));
        assertEquals(0, friends.friendCount(1));

        // 仇敌：单向
        assertEquals("已加入仇敌列表。", controller.addFoe("丙").get("message"));
        assertTrue(friends.isFoeOf(1, 3));
        assertEquals("已移出仇敌列表。", controller.removeFoe("丙").get("message"));
        assertEquals("对方不在你的仇敌列表里。", controller.removeFoe("丙").get("message"));
    }

    @Test
    void friendApiRejectsSelfAndReportsRateLimit() {
        SocialProfileFriendApiController controller = friendController(SocialProfileFakes.viewer(1, "甲"), true);
        assertEquals("不能加自己为好友。", controller.requestFriend("甲").get("message"));

        rateLimit.limitedKeys.add(FriendsService.RATE_LIMIT_CATEGORY + "|1");
        assertTrue(String.valueOf(controller.requestFriend("乙").get("message")).contains("操作过于频繁"));
    }

    // ==================== 礼物 ====================

    @Test
    void giftApiSendsAndDeletesWithAuthorization() {
        assertEquals("请先登录。", giftController(Viewer.ANONYMOUS, true).send("乙", "star", null).get("message"));

        SocialProfileGiftApiController controller = giftController(SocialProfileFakes.viewer(1, "甲"), true);
        assertEquals("用户不存在。", controller.send("幽灵", "star", null).get("message"));
        assertEquals("不能给自己送礼物。", controller.send("甲", "star", null).get("message"));
        assertEquals("礼物不存在（请从清单里选择）。", controller.send("乙", "diamond", null).get("message"));

        Map<String, Object> sent = controller.send("乙", "star", "谢谢");
        assertEquals(Boolean.TRUE, sent.get("success"));
        assertEquals("礼物已送出。", sent.get("message"));
        assertEquals("星星", ((Map<?, ?>) sent.get("data")).get("name"), "礼物名走 i18n");
        long giftId = ((Number) ((Map<?, ?>) sent.get("data")).get("id")).longValue();

        // 只有当事人能删
        assertEquals("只有送礼人或收礼人能删除这份礼物。",
                giftController(SocialProfileFakes.viewer(3, "丙"), true).delete(giftId).get("message"));
        assertEquals("礼物已删除。", giftController(SocialProfileFakes.viewer(2, "乙"), true)
                .delete(giftId).get("message"));
        assertEquals("礼物记录不存在。", controller.delete(giftId).get("message"));
    }

    // ==================== 奖励 ====================

    @Test
    void awardApiRequiresAdminAndIsIdempotent() {
        assertEquals("请先登录。", awardController(Viewer.ANONYMOUS)
                .grant("乙", "barnstar", null).get("message"));

        // 登录但不是管理员 → 无权限
        assertEquals("只有管理员能授予 / 撤销奖励。",
                awardController(SocialProfileFakes.viewer(1, "甲")).grant("乙", "barnstar", null).get("message"));

        SocialProfileAwardApiController admin = awardController(
                SocialProfileFakes.viewer(1, "甲", "管理员"));
        assertEquals("用户不存在。", admin.grant("幽灵", "barnstar", null).get("message"));
        assertEquals("奖励不存在（请从清单里选择）。", admin.grant("乙", "nobel", null).get("message"));

        Map<String, Object> granted = admin.grant("乙", "barnstar", "干得漂亮");
        assertEquals(Boolean.TRUE, granted.get("success"));
        assertEquals("已授予奖励。", granted.get("message"));
        assertEquals("星章", ((Map<?, ?>) granted.get("data")).get("name"));
        long awardId = ((Number) ((Map<?, ?>) granted.get("data")).get("id")).longValue();

        // 幂等：再授予同一种返回同一行
        assertEquals(awardId, ((Number) ((Map<?, ?>) admin.grant("乙", "barnstar", null).get("data")).get("id"))
                .longValue());
        assertEquals(1, awardService.of(2, 10).size());

        // 撤销：只有管理员
        assertEquals("只有管理员能授予 / 撤销奖励。",
                awardController(SocialProfileFakes.viewer(2, "乙")).revoke(awardId).get("message"));
        assertEquals("已撤销奖励。", admin.revoke(awardId).get("message"));
        assertEquals("授予记录不存在。", admin.revoke(awardId).get("message"));
    }

    // ==================== 工具 ====================

    private SocialProfileApiController profileController(Viewer viewer, boolean enabled) {
        return new SocialProfileApiController(profiles, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(viewer), SocialProfileFakes.support(enabled), users);
    }

    private SocialProfileBoardApiController boardController(Viewer viewer, boolean enabled) {
        return new SocialProfileBoardApiController(board, users, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(viewer), SocialProfileFakes.support(enabled));
    }

    private SocialProfileFriendApiController friendController(Viewer viewer, boolean enabled) {
        return new SocialProfileFriendApiController(friends, users, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(viewer), SocialProfileFakes.support(enabled));
    }

    private SocialProfileGiftApiController giftController(Viewer viewer, boolean enabled) {
        return new SocialProfileGiftApiController(giftService, users, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(viewer), SocialProfileFakes.support(enabled));
    }

    private SocialProfileAwardApiController awardController(Viewer viewer) {
        return new SocialProfileAwardApiController(awardService, users, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(viewer), SocialProfileFakes.support(true));
    }
}
