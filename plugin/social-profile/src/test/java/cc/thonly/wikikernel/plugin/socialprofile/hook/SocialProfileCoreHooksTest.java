package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import cc.thonly.wikikernel.plugin.socialprofile.web.MessagesSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.UploadAvatarSpecialPage;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件贡献的界面槽位：用户页资料卡的**头像**（社交链接 / 头像上传入口都在下方面板里，不在资料卡里）、
 * 顶栏「私信（N）」入口、站点级脚本 / 样式注入，以及两个特殊页的模型（登录 / 开关 / 阅读 / 会话 / 收件箱）。
 */
class SocialProfileCoreHooksTest {

    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "u1-1.png")
            .add(2, "乙", "");
    private final SocialProfileFakes.FakeAvatars avatars = new SocialProfileFakes.FakeAvatars();
    private final SocialProfileFakes.FakeMessages store = new SocialProfileFakes.FakeMessages();

    // ==================== 用户页资料卡 ====================

    @Test
    void profileHookOnlyContributesAvatar() {
        UserProfileLinksHook hook = new UserProfileLinksHook(new AvatarSupport(avatars, users),
                SocialProfileFakes.support(true));

        // 资料卡只接管头像；链接行与头像下方「上传头像」都留空（社交入口只在下方插件面板出现一次）
        UserProfileContext other = profile(1, "甲", false, SocialProfileFakes.viewer(2, "乙"));
        assertEquals("/avatars/u1-1.png", hook.avatarUrl(other));
        assertNull(hook.linksHtml(other), "资料卡链接行不再塞社交入口：" + hook.linksHtml(other));
        assertNull(hook.avatarUploadUrl(other));

        // 本人也一样：链接行留空（「贡献」由 core 渲染，社交入口在面板按钮行）
        UserProfileContext self = profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"));
        assertNull(hook.linksHtml(self), "本人资料卡链接行也留空：" + hook.linksHtml(self));
        assertNull(hook.avatarUploadUrl(self), "头像下方不再放「上传头像」（面板按钮行里才有）："
                + hook.avatarUploadUrl(self));
    }

    @Test
    void profileHookFallsBackToDefaultAvatarAndSilencesWhenDisabled() {
        UserProfileLinksHook hook = new UserProfileLinksHook(new AvatarSupport(avatars, users),
                SocialProfileFakes.support(true));
        // 没上传过头像 → 默认头像；账号不存在（userId=0）→ 不接管（core 用默认头像）
        assertEquals(UserStore.DEFAULT_AVATAR_URL, hook.avatarUrl(profile(2, "乙", false, Viewer.ANONYMOUS)));
        assertNull(hook.avatarUrl(profile(0, "幽灵", false, Viewer.ANONYMOUS)));

        UserProfileLinksHook disabled = new UserProfileLinksHook(new AvatarSupport(avatars, users),
                SocialProfileFakes.support(false));
        assertNull(disabled.avatarUrl(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));
        assertNull(disabled.linksHtml(profile(1, "甲", true, SocialProfileFakes.viewer(1, "甲"))));
    }
    // ==================== head 注入 ====================

    @Test
    void headProviderInjectsAssetsAndMessagesWhenEnabled() {
        SocialProfileHeadProvider provider = new SocialProfileHeadProvider(
                SocialProfileFakes.support(true));
        assertTrue(provider.siteWide());
        String head = String.join("\n", provider.headItems(null));
        assertTrue(head.contains("/plugins/social-profile/social-profile-base.css"), head);
        assertTrue(head.contains("/plugins/social-profile/avatar.js"), head);
        assertTrue(head.contains("/plugins/social-profile/messages.js"), head);
        assertTrue(head.contains("WIKI_CONFIG.userInteraction = true"), head);
        assertTrue(head.contains("avatarSelect:"), head);
        assertTrue(head.contains("messageDeleteConfirm:"), head);

        SocialProfileHeadProvider disabled = new SocialProfileHeadProvider(
                SocialProfileFakes.support(false));
        assertEquals(List.of(), disabled.headItems(null));
    }

    // ==================== 特殊:上传头像 ====================

    @Test
    void uploadAvatarSpecialPagePopulatesLoginSwitchAndCurrentAvatar() {
        UploadAvatarSpecialPage page = new UploadAvatarSpecialPage(new AvatarSupport(avatars, users), users,
                SocialProfileFakes.support(true));
        assertEquals("上传头像", page.name());
        assertEquals(List.of("UploadAvatar", "Avatar", "MyAvatar"), page.aliases());
        assertEquals(SpecialPageCategories.LOGIN, page.category());
        assertEquals("special/upload-avatar", page.template());

        // 匿名：提示登录，不填头像
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("上传头像", Map.of(), null), anonymous);
        assertEquals(Boolean.TRUE, anonymous.get("avatarLogin"));
        assertFalse(anonymous.containsKey("avatarUrl"));

        // 登录：当前头像 + 用户页地址
        Map<String, Object> model = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("上传头像", Map.of(), wikiUser(1, "甲")), model);
        assertEquals(Boolean.FALSE, model.get("featureDisabled"));
        assertEquals("甲", model.get("avatarUserName"));
        assertEquals("/avatars/u1-1.png", model.get("avatarUrl"));
        assertEquals("/wiki/" + URLEncoder.encode("用户:甲", StandardCharsets.UTF_8).replace("+", "%20"),
                model.get("avatarUserPageUrl"));

        // 功能关闭：只给「已关闭」提示
        UploadAvatarSpecialPage disabled = new UploadAvatarSpecialPage(new AvatarSupport(avatars, users), users,
                SocialProfileFakes.support(false));
        Map<String, Object> closed = new LinkedHashMap<>();
        disabled.populate(new SpecialPageRequest("上传头像", Map.of(), wikiUser(1, "甲")), closed);
        assertEquals(Boolean.TRUE, closed.get("featureDisabled"));
        assertFalse(closed.containsKey("avatarUserName"));
    }

    // ==================== 特殊:私信 ====================

    @Test
    void messagesSpecialPageCoversInboxComposeThreadAndReading() {
        MessageCenter center = new MessageCenter(store, users);
        MessagesSpecialPage page = new MessagesSpecialPage(center, users, SocialProfileFakes.wikiText(),
                SocialProfileFakes.support(true));

        assertEquals("私信", page.name());
        assertEquals(SpecialPageCategories.LOGIN, page.category());
        assertEquals("special/messages", page.template());

        // 匿名：提示登录
        Map<String, Object> anonymous = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of(), null), anonymous);
        assertEquals(Boolean.TRUE, anonymous.get("messagesLogin"));

        // 甲 → 乙（wikitext 粗体）
        var sent = center.send(1, "乙", "你好 '''乙'''");

        // 乙的收件箱：未读高亮 + 摘要 + 对方用户名
        Map<String, Object> inbox = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of(), wikiUser(2, "乙")), inbox);
        assertEquals("inbox", inbox.get("messagesMode"));
        assertEquals(1L, inbox.get("unreadCount"));
        List<?> rows = (List<?>) inbox.get("messageRows");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("甲", row.get("other"));
        assertEquals(Boolean.TRUE, row.get("unread"));
        assertEquals("你好 '''乙'''", row.get("excerpt"));

        // 阅读（?id=）：wikitext 渲染 + 自动标已读（与迁移前一致：正文区下方仍显示收件箱列表）
        Map<String, Object> view = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("id", String.valueOf(sent.getId())), wikiUser(2, "乙")),
                view);
        assertEquals("inbox", view.get("messagesMode"));
        assertEquals(Boolean.TRUE, view.get("messageView"));
        assertTrue(String.valueOf(view.get("messageHtml")).contains("<b>乙</b>"),
                "私信正文按 wikitext 渲染：" + view.get("messageHtml"));
        assertEquals(Boolean.TRUE, view.get("messageInbox"));
        assertEquals(0, center.unreadCount(2));

        // 无关用户看不到这条私信
        Map<String, Object> stranger = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("id", String.valueOf(sent.getId())),
                wikiUser(3, "丙")), stranger);
        assertEquals(Boolean.TRUE, stranger.get("messageNotFound"));

        // 写私信（?write=1&to=）：预填收件人
        Map<String, Object> compose = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("write", "1", "to", "乙"), wikiUser(1, "甲")), compose);
        assertEquals("compose", compose.get("messagesMode"));
        assertEquals("乙", compose.get("composeTo"));

        // 发件箱
        Map<String, Object> sentBox = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("box", "sent"), wikiUser(1, "甲")), sentBox);
        assertEquals("sent", sentBox.get("messagesMode"));
        assertEquals(Boolean.TRUE, sentBox.get("messageBoxSent"));
        assertEquals(1, ((List<?>) sentBox.get("messageRows")).size());

        // 会话：两个方向按时间拼接 + 打开即已读
        center.send(2, "甲", "回信");
        Map<String, Object> thread = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("with", "乙"), wikiUser(1, "甲")), thread);
        assertEquals("thread", thread.get("messagesMode"));
        assertEquals("乙", thread.get("threadPeer"));
        assertEquals(2, ((List<?>) thread.get("threadRows")).size());
        assertEquals(0, center.unreadCount(1));

        // 会话列表：按对方聚合（最后一条 / 条数）
        Map<String, Object> conversations = new LinkedHashMap<>();
        page.populate(new SpecialPageRequest("私信", Map.of("box", "conversations"), wikiUser(1, "甲")),
                conversations);
        assertEquals("conversations", conversations.get("messagesMode"));
        List<?> conversationRows = (List<?>) conversations.get("conversationRows");
        assertEquals(1, conversationRows.size());
        Map<?, ?> conversation = (Map<?, ?>) conversationRows.get(0);
        assertEquals("乙", conversation.get("peer"));
        assertEquals(2, conversation.get("count"));
        assertEquals("回信", conversation.get("excerpt"));

        // 功能关闭：只给「已关闭」提示
        MessagesSpecialPage disabled = new MessagesSpecialPage(center, users, SocialProfileFakes.wikiText(),
                SocialProfileFakes.support(false));
        Map<String, Object> closed = new LinkedHashMap<>();
        disabled.populate(new SpecialPageRequest("私信", Map.of(), wikiUser(1, "甲")), closed);
        assertEquals(Boolean.TRUE, closed.get("featureDisabled"));
        assertFalse(closed.containsKey("messageRows"));
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

    private static WikiUser wikiUser(long id, String username) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
