package cc.thonly.wikikernel.plugin.socialprofile;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.MessagesToolbarHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.PointsViewHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.SocialPreferencesSection;
import cc.thonly.wikikernel.plugin.socialprofile.hook.SocialProfilePageDisplayHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.UserProfileLinksHook;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsRulesProvider;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import cc.thonly.wikikernel.plugin.socialprofile.service.SocialProfileView;
import cc.thonly.wikikernel.plugin.socialprofile.web.AwardsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.EditProfileSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.FriendsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.GiftsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.MessagesSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.RandomUsersSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.SendGiftSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.TopUsersSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.UploadAvatarSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.UserBoardSpecialPage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件入口：注册的扩展点（10 个特殊页、head 注入、资料卡 / 顶栏 / 页面显示 / 浏览钩子、参数设置分区、
 * 消息包）、描述信息与开关键常量（必须与 core 的站点设置一致）。
 */
class SocialProfilePluginTest {

    /** SocialProfile 新增的消息键（每个都要在 zh / en 两个消息包里；改名前的键前缀保持现状）。 */
    private static final String[] SOCIAL_PROFILE_KEYS = {
            "socialProfile.editProfile", "socialProfile.profile.saved", "socialProfile.error.birthday",
            "socialProfile.board.title", "socialProfile.board.posted", "socialProfile.error.boardClosed",
            "socialProfile.friends.title", "socialProfile.friends.requested", "socialProfile.error.friendSelf",
            "socialProfile.foes.added", "socialProfile.error.foeNotFoe",
            "socialProfile.gifts.title", "socialProfile.gifts.sent", "socialProfile.error.giftUnknown",
            "socialProfile.gift.star", "socialProfile.gift.kitten",
            "socialProfile.topUsers.title", "socialProfile.level.1", "socialProfile.level.5",
            "socialProfile.level.other", "socialProfile.points.summary",
            "socialProfile.awards.title", "socialProfile.awards.granted", "socialProfile.error.awardForbidden",
            "socialProfile.award.barnstar", "socialProfile.award.socialButterfly",
            "socialProfile.randomUsers.title", "socialProfile.preferences.title",
            "socialProfile.preferences.allowBoard", "special.editProfile", "special.userBoard",
            "special.friends", "special.sendGift", "special.gifts", "special.topUsers", "special.awards",
            "special.randomUsers", "socialProfile.error.rateLimited", "socialProfile.error.failed"};

    @Test
    void descriptorMatchesThePortedFeature() {
        SocialProfilePlugin plugin = new SocialProfilePlugin();
        assertEquals("social-profile", plugin.descriptor().id());
        assertEquals("SocialProfile", plugin.descriptor().name());
        assertEquals("features.social-profile", SocialProfilePlugin.ENABLED_KEY);
    }

    @Test
    void legacyUserInteractionKeyStillTogglesTheFeature() {
        // 插件改名（user-interaction → social-profile）后，旧开关键仍然生效：新键没设置时回落到旧键
        assertTrue(new SocialProfileSupport(SocialProfileFakes.config(
                SocialProfileSupport.LEGACY_ENABLED_KEY, "true", Map.of()), null).enabled());
        assertFalse(new SocialProfileSupport(SocialProfileFakes.config(
                SocialProfileSupport.LEGACY_ENABLED_KEY, "false", Map.of()), null).enabled());
        // 新键优先：旧键开着、新键关掉 → 关
        assertFalse(new SocialProfileSupport(SocialProfileFakes.config(SocialProfileSupport.ENABLED_KEY,
                "false", Map.of(SocialProfileSupport.LEGACY_ENABLED_KEY, "true")), null).enabled());
    }

    @Test
    void onStartRegistersSpecialPagesHooksAndMessages() {
        PluginContextFake context = PluginContextFake.create();
        SocialProfilePlugin plugin = wire(pluginWithStubs());

        plugin.onStart(context);

        // 10 个特殊页：2 个原有（头像 / 私信）+ 8 个 SocialProfile
        List<SpecialPage> pages = context.lookup(SpecialPage.class);
        assertEquals(10, pages.size());
        assertTrue(pages.stream().anyMatch(UploadAvatarSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(MessagesSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(EditProfileSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(UserBoardSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(FriendsSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(SendGiftSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(GiftsSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(TopUsersSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(AwardsSpecialPage.class::isInstance));
        assertTrue(pages.stream().anyMatch(RandomUsersSpecialPage.class::isInstance));

        assertInstanceOf(HtmlHeadProvider.class, context.lookup(HtmlHeadProvider.class).get(0));
        // 两个 UserProfileHook：资料卡链接（order 100）+ 资料卡下方/正文之前的 SocialProfile 面板（order 300）
        List<UserProfileHook> profileHooks = context.lookup(UserProfileHook.class);
        assertEquals(2, profileHooks.size(), "链接钩子 + 面板钩子");
        assertEquals(100, profileHooks.get(0).order());
        assertEquals(300, profileHooks.get(1).order());
        assertInstanceOf(PersonalToolbarHook.class, context.lookup(PersonalToolbarHook.class).get(0));
        assertTrue(context.lookup(PageDisplayHook.class).isEmpty(),
                "用户页板面改走 UserProfileHook.panelHtml 之后不再注册 PageDisplayHook");
        assertInstanceOf(PageViewHook.class, context.lookup(PageViewHook.class).get(0));
        assertInstanceOf(PreferenceSection.class, context.lookup(PreferenceSection.class).get(0));

        // 消息包：原有键 + SocialProfile 新键都要在 zh / en 里
        assertEquals(1, context.lookup(MessageBundle.class).size());
        Map<String, Map<String, String>> messagesByLanguage = context.lookup(MessageBundle.class).get(0).messages();
        for (String key : new String[]{"avatar.upload", "avatar.notImage", "avatar.saved",
                "special.uploadAvatar", "special.messages", "messages.link", "messages.notFound",
                "messages.selfNotAllowed", "messages.deleteConfirm", "tools.sendMessage"}) {
            assertTrue(messagesByLanguage.get("zh").containsKey(key), key + " 应在中文消息包里");
            assertTrue(messagesByLanguage.get("en").containsKey(key), key + " 应在英文消息包里");
        }
        for (String key : SOCIAL_PROFILE_KEYS) {
            assertTrue(messagesByLanguage.get("zh").containsKey(key), key + " 应在中文消息包里");
            assertTrue(messagesByLanguage.get("en").containsKey(key), key + " 应在英文消息包里");
        }
        assertEquals(messagesByLanguage.get("zh").keySet(), messagesByLanguage.get("en").keySet(),
                "中英文消息包的键集合必须完全一致");
    }

    @Test
    void toolbarAndPreferenceSectionComeFromTheSameBeansAsThePages() {
        // 顶栏与参数设置分区用的是插件里 @Autowired 的 Bean（单元测试里手工装配）
        MessagesToolbarHook toolbar = new MessagesToolbarHook(null, SocialProfileFakes.support(true));
        SocialPreferencesSection section = new SocialPreferencesSection(null,
                SocialProfileFakes.support(true));
        assertEquals("social-profile", section.id());
        assertEquals("socialProfile.preferences.title", section.labelKey());
        assertEquals(MessagesToolbarHook.ITEM_CLASS, "personal-messages");
        assertTrue(toolbar.friendsItemHtml(2).contains("personal-friends"));
        assertTrue(toolbar.giftsItemHtml(3).contains("personal-gifts"));
        assertTrue(toolbar.giftsItemHtml(0).contains("礼物"));
    }

    /** 建插件实例（不依赖 Spring 的字段注入）。 */
    private static SocialProfilePlugin pluginWithStubs() {
        return new SocialProfilePlugin();
    }

    /** 给插件的 {@code @Autowired} 字段塞测试替身（单元测试没有 Spring 容器）。 */
    private static SocialProfilePlugin wire(SocialProfilePlugin plugin) {
        SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers().add(1, "甲", "").add(2, "乙", "");
        SocialProfileFakes.FakeSettings settings = new SocialProfileFakes.FakeSettings();
        SocialProfileFakes.FakeStats stats = new SocialProfileFakes.FakeStats(users);
        SocialProfileFakes.FakeRateLimit rateLimit = new SocialProfileFakes.FakeRateLimit();
        SocialProfileSupport support = SocialProfileFakes.support(true);
        PointsService points = new PointsService(stats, support);
        ProfileService profiles = new ProfileService(settings, users, support, points);
        BoardService board = new BoardService(new SocialProfileFakes.FakeBoards(), users, rateLimit, support,
                profiles, points);
        FriendsService friends = new FriendsService(new SocialProfileFakes.FakeRelations(), users, rateLimit,
                support, points);
        GiftService gifts = new GiftService(new SocialProfileFakes.FakeGifts(), users, rateLimit, support,
                points, settings);
        AwardService awards = new AwardService(new SocialProfileFakes.FakeAwards(), users, support);
        MessageCenter messages = new MessageCenter(new SocialProfileFakes.FakeMessages(), users);

        setField(plugin, "avatars", new AvatarSupport(null, users));
        setField(plugin, "messages", messages);
        setField(plugin, "support", support);
        setField(plugin, "profiles", profiles);
        setField(plugin, "board", board);
        setField(plugin, "friends", friends);
        setField(plugin, "gifts", gifts);
        setField(plugin, "points", points);
        setField(plugin, "pointsRules", new PointsRulesProvider(support));
        setField(plugin, "awards", awards);
        setField(plugin, "socialProfileView", new SocialProfileView(support, profiles, points,
                SocialProfileFakes.wikiText()));
        setField(plugin, "toolbar", new MessagesToolbarHook(messages, support, friends, gifts));
        setField(plugin, "userStore", users);
        setField(plugin, "userSettings", settings);
        setField(plugin, "userBoardStore", new SocialProfileFakes.FakeBoards());
        setField(plugin, "userRelationStore", new SocialProfileFakes.FakeRelations());
        setField(plugin, "userGiftStore", new SocialProfileFakes.FakeGifts());
        setField(plugin, "userStatsStore", stats);
        setField(plugin, "userAwardStore", new SocialProfileFakes.FakeAwards());
        return plugin;
    }

    /** 反射给 {@code @Autowired} 字段赋值（单元测试没有 Spring 容器）。 */
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法设置字段 " + name, exception);
        }
    }
}
