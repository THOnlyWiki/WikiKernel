package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import cc.thonly.wikikernel.plugin.socialprofile.service.SocialProfileView;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SocialProfile 贡献的界面槽位：
 * <ul>
 *     <li>{@link UserProfileLinksHook}：资料卡的**头像**（链接行不再有社交入口：私信 / 留言板（N）/
 *     好友（N）/ 礼物（N）/ 积分排行 / 编辑资料 / 上传头像全部只在下方插件面板里出现一次）；</li>
 *     <li>{@link SocialProfilePageDisplayHook}：用户页正文下方的资料字段 + 留言板面板（最近 N 条 + 输入框）；</li>
 *     <li>{@link SocialPreferencesSection}：参数设置里的「社交资料」分区。</li>
 * </ul>
 */
class SocialProfileHooksTest {

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
    private final SocialProfileSupport support = SocialProfileFakes.support(true);

    private final PointsService points = new PointsService(stats, support);
    private final ProfileService profiles = new ProfileService(settings, users, support, points);
    private final BoardService board = new BoardService(boards, users, rateLimit, support, profiles, points);
    private final FriendsService friends = new FriendsService(relations, users, rateLimit, support, points);
    private final GiftService giftService = new GiftService(gifts, users, rateLimit, support, points, settings);
    private final AwardService awardService = new AwardService(awards, users, support);
    private final AvatarSupport avatars = new AvatarSupport(new SocialProfileFakes.FakeAvatars(), users);
    private final SocialProfileView view = new SocialProfileView(support, profiles, points,
            SocialProfileFakes.wikiText());

    // ==================== 资料卡链接 ====================

    @Test
    void profileLinksKeepCardFreeOfSocialEntries() {
        board.post(2, 1, "给甲的留言");
        giftService.send(2, 1, "star", "");
        friends.requestFriend(2, 1);
        friends.accept(1, relations.find(2, 1, "FRIEND").orElseThrow().getId());

        UserProfileLinksHook hook = new UserProfileLinksHook(avatars, support, board, friends, giftService);
        // 资料卡链接行留空：社交入口（私信 / 留言板 / 好友 / 礼物 / 积分排行 / 编辑资料 / 上传头像）
        // 一律只在下方插件面板的操作按钮行与统计栏里出现一次（内容由 pageDisplayHook 的用例覆盖）
        assertNull(hook.linksHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙"))));
        assertNull(hook.linksHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));

        // 头像仍然接管：上传过的用上传的，没上传的用默认头像
        assertEquals("/avatars/u1-1.png",
                hook.avatarUrl(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));
        assertEquals(UserStore.DEFAULT_AVATAR_URL,
                hook.avatarUrl(profile(2, "乙", false, SocialProfileFakes.viewer(1, "甲"))));

        // 功能关闭：什么都不贡献（头像也不接管，core 用默认头像）
        UserProfileLinksHook disabled = new UserProfileLinksHook(avatars, SocialProfileFakes.support(false),
                board, friends, giftService);
        assertNull(disabled.linksHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));
        assertNull(disabled.avatarUrl(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));

        // 旧构造器（没有社交服务）行为一致：链接仍然留空、头像照常接管
        UserProfileLinksHook legacy = new UserProfileLinksHook(avatars, support);
        assertNull(legacy.linksHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));
        assertEquals("/avatars/u1-1.png", legacy.avatarUrl(profile(1, "甲", true,
                SocialProfileFakes.viewer(1, "甲"))));
    }

    // ==================== 用户页（SocialProfile 原版版式） ====================

    @Test
    void profileTopRendersSocialProfileHeader() {
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, true, true, true));
        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);

        // 陌生访客：加为好友 / 加为仇敌 / 发送消息 / 送一份礼物 / 贡献
        String top = hook.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertTrue(top.contains("id=\"profile-top\""), top);
        assertTrue(top.contains("id=\"profile-image\""), top);
        assertTrue(top.contains("width=\"75\" height=\"75\""), "75×75 头像（原版 wAvatar 'l'）：" + top);
        assertTrue(top.contains("id=\"profile-title\">甲</div>"), top);
        assertTrue(top.contains("id=\"points-level\""), "红色积分徽章：" + top);
        assertTrue(top.contains("class=\"profile-actions\""), top);
        assertTrue(top.contains("data-friend-add=\"甲\""), top);
        assertTrue(top.contains("加为好友"), top);
        assertTrue(top.contains("sp-foe-add"), "加为仇敌：" + top);
        assertTrue(top.contains("发送消息"), top);
        assertTrue(top.contains("送一份礼物"), top);
        assertTrue(top.contains(">贡献</a>"), top);

        // 本人：编辑个人资料 / 上传头像 / 监视列表 / 贡献
        String self = hook.profileTopHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲")));
        assertTrue(self.contains("编辑个人资料"), self);
        assertTrue(self.contains("上传头像"), self);
        assertTrue(self.contains("监视列表"), self);
        assertFalse(self.contains("data-friend-add"), "本人不显示加好友：" + self);

        // 账号不存在：只有贡献链接
        String missing = hook.profileTopHtml(profile(0, "幽灵", false, SocialProfileFakes.viewer(1, "甲")));
        assertTrue(missing.contains("id=\"profile-top\""), missing);
        assertTrue(missing.contains(">贡献</a>"), missing);
        assertFalse(missing.contains("data-friend-add"), missing);
        assertFalse(missing.contains("id=\"points-level\""), missing);
    }

    @Test
    void pageDisplayHookRendersSocialProfileColumnsAndBoard() {
        profiles.save(1, new ProfileFields("male", "03-09", "上海", "杭州", "程序员", "写代码",
                "https://example.com", "'''你好'''"));
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, true, true, true));
        board.post(2, 1, "留言内容");
        giftService.send(2, 1, "star", "");
        awardService.grant(1, true, 1, "barnstar", "");

        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);
        assertEquals(300, hook.order());
        String html = hook.panelHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));

        // 原版两栏：左 35% 统计 / 礼物 / 勋章；右 60% 个人信息 / 用户墙
        assertTrue(html.contains("id=\"user-page-left\""), html);
        assertTrue(html.contains("id=\"user-page-right\""), html);
        assertTrue(html.contains("class=\"user-section-heading\""), html);
        assertTrue(html.contains("<div class=\"user-section-title\">统计</div>"), html);
        assertTrue(html.contains("<div class=\"user-section-title\">礼物</div>"), html);
        assertTrue(html.contains("<div class=\"user-section-title\">勋章</div>"), html);
        assertTrue(html.contains("<div class=\"user-section-title\">个人信息</div>"), html);
        assertTrue(html.contains("<div class=\"user-section-title\">用户墙</div>"), html);

        // 个人信息：`<b>标签</b>值` 行（原版 getProfileSection）+ 关于我按 wikitext 渲染
        assertTrue(html.contains("<b>性别</b> 男"), html);
        assertTrue(html.contains("<b>生日</b> 03-09"), html);
        assertTrue(html.contains("<b>现居地</b> 上海"), html);
        assertTrue(html.contains("<b>家乡</b> 杭州"), html);
        assertTrue(html.contains("<b>职业</b> 程序员"), html);
        assertTrue(html.contains("<b>兴趣</b> 写代码"), html);
        assertTrue(html.contains("https://example.com"), html);
        assertTrue(html.contains("<b>你好</b>"), "关于我按 wikitext 渲染：" + html);

        // 用户墙：原版留言条目结构 + 内联输入框 + 查看全部
        assertTrue(html.contains("id=\"user-page-board\""), html);
        assertTrue(html.contains("/api/social-profile/board/post"), "能给他人留言：" + html);
        assertTrue(html.contains("留言内容"), html);
        assertTrue(html.contains("user-board-message\""), "原版留言条目：" + html);
        assertTrue(html.contains("class=\"user-board-message-from\""), html);
        assertTrue(html.contains("class=\"user-board-message-body\""), html);
        assertTrue(html.contains("查看全部"), html);
    }

    @Test
    void pageDisplayHookShowsRelationStatePerFriendship() {
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, true, true, true));
        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);

        // 陌生：给「加为好友」与「加为仇敌」
        String none = hook.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertTrue(none.contains("加为好友"), none);
        assertTrue(none.contains("加为仇敌"), none);

        // 乙 → 甲 的请求待处理：乙 看到「好友请求待处理」
        friends.requestFriend(2, 1);
        String pending = hook.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertTrue(pending.contains("好友请求待处理"), pending);

        // 甲 接受后：双方都是「从好友列表移除」
        friends.accept(1, relations.find(2, 1, WikiUserRelation.TYPE_FRIEND).orElseThrow().getId());
        String friend = hook.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertTrue(friend.contains("从好友列表移除"), "已是好友：" + friend);
        assertTrue(friend.contains("sp-friend-remove"), friend);

        // 甲 把乙列为仇敌：乙 看到「从仇敌列表移除」（关系为 FOE 视角）
        friends.removeFriend(1, 2);
        friends.addFoe(1, 2);
        String foe = hook.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertTrue(foe.contains("加为好友") || foe.contains("从仇敌列表移除"), foe);
    }

    @Test
    void pageDisplayHookHidesFieldsByPreferenceAndDisablesPosting() {
        profiles.save(1, new ProfileFields("female", "03-09", "上海", "", "", "", "", ""));
        // 先让别人留言，之后再关掉「允许他人留言」
        board.post(2, 1, "留言内容");
        giftService.send(2, 1, "star", "");
        awardService.grant(1, true, 1, "barnstar", "");
        profiles.savePreferences(1, new ProfileService.SocialPreferences(false, false, false, false, false));

        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);

        // 展示开关全关：生日 / 现居地整行不出现（本人视角与别人视角都一样）
        String self = hook.panelHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲")));
        assertFalse(self.contains("03-09"), "关掉的生日不显示：" + self);
        assertFalse(self.contains("上海"), "关掉的现居地不显示：" + self);
        assertTrue(self.contains("<b>性别</b> 女"), "没关的字段仍然显示：" + self);
        assertFalse(self.contains("/api/social-profile/board/post"), "自己的板不给输入框：" + self);
        assertTrue(self.contains("留言内容"), "留言本身仍然是公开的：" + self);
        // 本人顶部：编辑个人资料 / 上传头像 / 监视列表
        String selfTop = hook.profileTopHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲")));
        assertTrue(selfTop.contains("编辑个人资料") && selfTop.contains("上传头像")
                && selfTop.contains("监视列表"), selfTop);

        String other = hook.panelHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));
        assertFalse(other.contains("03-09"), "别人也看不到关掉的生日：" + other);
        assertTrue(other.contains("该用户已关闭留言板。"), other);
        assertFalse(other.contains("/api/social-profile/board/post"), other);

        // 匿名 + 对方开着留言板：提示登录，不给输入框
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, true, true, true));
        String anonymous = hook.panelHtml(profile(1, "甲", false, Viewer.ANONYMOUS));
        assertTrue(anonymous.contains("user-page-board"), anonymous);
        assertTrue(anonymous.contains("请登录后在用户墙上留言。"), anonymous);
        assertFalse(anonymous.contains("/api/social-profile/board/post"), anonymous);
        // 匿名顶部只有贡献
        String anonymousTop = hook.profileTopHtml(profile(1, "甲", false, Viewer.ANONYMOUS));
        assertTrue(anonymousTop.contains(">贡献</a>"), anonymousTop);
        assertFalse(anonymousTop.contains("加为好友"), anonymousTop);
    }

    @Test
    void pageDisplayHookHandlesNotYetCreatedUserPages() {
        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);
        // 标题为空 / 子页面：不贡献面板
        assertEquals("", hook.panelHtml(profile(0, "", false, SocialProfileFakes.viewer(1, "甲"))));
        assertEquals("", hook.panelHtml(profile(1, "甲/子页", false, SocialProfileFakes.viewer(2, "乙"))),
                "用户子页面不挂留言板（避免同一份留言出现在多个页面）");

        // 账号不存在（userId = 0）：留言墙仍在；统计 / 个人信息 / 好友入口不显示
        String missing = hook.panelHtml(profile(0, "幽灵", false, SocialProfileFakes.viewer(1, "甲")));
        assertTrue(missing.contains("id=\"user-page-board\""), "未创建的用户页也有留言墙：" + missing);
        assertTrue(missing.contains("没有消息。"), missing);
        assertFalse(missing.contains("个人信息"), "没有账号就没有个人信息：" + missing);
        assertFalse(missing.contains("user-relationship-container"), "没有账号就没有好友列表：" + missing);
        String missingTop = hook.profileTopHtml(profile(0, "幽灵", false, SocialProfileFakes.viewer(1, "甲")));
        assertFalse(missingTop.contains("加为好友"), "账号不存在时不给加好友链接：" + missingTop);
        assertFalse(missingTop.contains("发送消息"), "账号不存在时不给私信链接：" + missingTop);

        // 功能关闭：不渲染
        SocialProfilePageDisplayHook disabled = new SocialProfilePageDisplayHook(users,
                SocialProfileFakes.support(false), profiles, board, friends, giftService, awardService, view);
        assertEquals("", disabled.panelHtml(profile(1, "甲", false, SocialProfileFakes.viewer(1, "甲"))));
        assertEquals("", disabled.profileTopHtml(profile(1, "甲", false, SocialProfileFakes.viewer(1, "甲"))));
    }

    @Test
    void userPagePanelRendersBoardMessagesInOriginalMarkup() {
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, true, true, true));
        board.post(2, 1, "第一楼");
        board.post(3, 1, "第二楼");
        board.reply(3, 1, "回复第一楼");

        SocialProfilePageDisplayHook hook = new SocialProfilePageDisplayHook(users, support, profiles, board,
                friends, giftService, awardService, view);
        String html = hook.panelHtml(profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙")));

        int first = html.indexOf("第一楼");
        int second = html.indexOf("第二楼");
        assertTrue(first >= 0 && second > first, "留言按时间顺序排列：" + html);
        assertTrue(html.contains("user-board-message-time"), "相对时间行：" + html);
        assertTrue(html.contains("在") && html.contains("前发布"), "相对时间文案：" + html);
        // 原版条目结构出现多条
        assertTrue(countOf(html, "user-board-message\"") >= 3, "回复也按原版条目渲染：" + html);
    }
    // ==================== 顶栏 ====================

    // ==================== 参数设置分区 ====================

    @Test
    void preferenceSectionRendersCheckboxesAndSavesThroughThePluginApi() {
        SocialPreferencesSection section = new SocialPreferencesSection(profiles, support);
        assertEquals("social-profile", section.id());
        assertEquals("socialProfile.preferences.title", section.labelKey());
        assertEquals("社交资料", section.fallbackLabel());

        String html = section.html(preferenceContext(SocialProfileFakes.viewer(1, "甲")));
        assertTrue(html.contains("data-api=\"/api/social-profile/preferences\""), html);
        for (String name : new String[]{"showBirthday", "showLocation", "allowBoard", "showGifts", "showAwards"}) {
            assertTrue(html.contains("name=\"" + name + "\""), name + " 应有复选框：" + html);
        }
        assertTrue(html.contains("checked"), "默认全部勾选：" + html);

        // 关掉「允许留言」后再渲染：该复选框不再有 checked
        profiles.savePreferences(1, new ProfileService.SocialPreferences(true, true, false, true, true));
        String after = section.html(preferenceContext(SocialProfileFakes.viewer(1, "甲")));
        assertTrue(after.contains("name=\"allowBoard\""), after);
        int allowIndex = after.indexOf("name=\"allowBoard\"");
        int labelEnd = after.indexOf("</label>", allowIndex);
        assertFalse(after.substring(allowIndex, labelEnd).contains("checked"),
                "关闭的偏好不应带 checked：" + after.substring(allowIndex, labelEnd));

        // 功能关闭：只给提示
        SocialPreferencesSection disabled = new SocialPreferencesSection(profiles,
                SocialProfileFakes.support(false));
        String closed = disabled.html(preferenceContext(SocialProfileFakes.viewer(1, "甲")));
        assertFalse(closed.contains("<form"), closed);
        assertTrue(closed.contains("站点已关闭"), "功能关闭时分区只给提示：" + closed);
    }

    // ==================== 工具 ====================

    private static UserProfileContext profile(long userId, String username, boolean self, Viewer viewer) {
        return new UserProfileContext() {
            @Override
            public long userId() {
                return userId;
            }

            @Override
            public String username() {
                return username;
            }

            @Override
            public boolean self() {
                return self;
            }

            @Override
            public Viewer viewer() {
                return viewer;
            }

            @Override
            public String defaultAvatarUrl() {
                return UserStore.DEFAULT_AVATAR_URL;
            }
        };
    }

    private static PageDisplayContext display(String title, Viewer viewer) {
        return new PageDisplayContext() {
            @Override
            public long pageId() {
                return 1;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public String content() {
                return "";
            }

            @Override
            public boolean oldRevision() {
                return false;
            }

            @Override
            public String pageUrl() {
                return "/wiki/" + title;
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Viewer viewer() {
                return viewer;
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }
        };
    }

    /** 子串在 HTML 里出现的次数（断言「同一入口只出现一次」用）。 */
    private static int countOf(String html, String needle) {
        int count = 0;
        int index = html == null ? -1 : html.indexOf(needle);
        while (index >= 0) {
            count++;
            index = html.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static PreferenceContext preferenceContext(Viewer viewer) {
        Map<String, String> messages = new LinkedHashMap<>();
        return new PreferenceContext() {
            @Override
            public Viewer viewer() {
                return viewer;
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }

            @Override
            public String message(String key, String fallback) {
                return messages.computeIfAbsent(key, ignored -> SocialProfileFakes.messages()
                        .getMessage(key, null, key, java.util.Locale.getDefault()));
            }
        };
    }
}
