package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 顶栏个人工具区的用户交互入口（仿 MediaWiki 的 PersonalUrls 钩子）：未登录不显示。
 * <ul>
 *     <li>{@code 私信（N）}：有未读私信时带计数（迁移前 core 的行为，键 / 类名不变）；</li>
 *     <li>{@code 好友（N）}：有待处理的好友请求时带计数（SocialProfile 的好友请求提醒）；</li>
 *     <li>{@code 礼物（N）}：有还没查看过的新礼物时带计数（已读水位存用户偏好
 *     {@code socialProfile.gifts.seenAt}）；</li>
 * </ul>
 * 迁移前这段 HTML 在 core 的 {@code fragments/personal-tools.html} 里硬编码、未读数由
 * {@code CurrentUserAdvice.unreadMessages} 计算；现在由插件通过 {@link PersonalToolbarHook} 贡献。
 */
@Component
public final class MessagesToolbarHook implements PersonalToolbarHook {

    /** 「私信（N）」条目的稳定标记（特殊:私信页刷新未读徽标时按它替换旧条目）。 */
    public static final String ITEM_CLASS = "personal-messages";

    /** 「好友（N）」条目的标记。 */
    public static final String FRIENDS_ITEM_CLASS = "personal-friends";

    /** 「礼物（N）」条目的标记。 */
    public static final String GIFTS_ITEM_CLASS = "personal-gifts";

    private final MessageCenter messages;
    private final SocialProfileSupport support;
    private final FriendsService friends;
    private final GiftService gifts;

    public MessagesToolbarHook(MessageCenter messages, SocialProfileSupport support) {
        this(messages, support, null, null);
    }

    @Autowired
    public MessagesToolbarHook(MessageCenter messages, SocialProfileSupport support, FriendsService friends,
                               GiftService gifts) {
        this.messages = messages;
        this.support = support;
        this.friends = friends;
        this.gifts = gifts;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String itemsHtml(PersonalToolbarContext context) {
        if (!support.enabled() || context == null) {
            return null;
        }
        Viewer viewer = context.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return null;
        }
        StringBuilder html = new StringBuilder(itemHtml(messages.unreadCount(viewer.id())));
        if (friends != null) {
            long pending = friends.pendingCount(viewer.id());
            html.append(friendsItemHtml(pending));
        }
        if (gifts != null) {
            long unseen = gifts.unseenCount(viewer.id());
            html.append(giftsItemHtml(unseen));
        }
        return html.toString();
    }

    /**
     * 「私信（N）」条目 HTML（{@code <li>} 级别）。
     * <p>
     * 单独暴露给特殊:私信：那边阅读 / 打开会话时会立刻把未读标为已读，而 core 的个人工具条目在
     * 进入控制器**之前**就已收集，所以由该页用最新计数覆盖同一次响应里的这一条，保证徽标同步。
     */
    public String itemHtml(long unread) {
        return itemHtml(ITEM_CLASS, "messages.link", "messages.title", "/wiki/特殊:私信", unread);
    }

    /** 「好友（N）」条目（有未处理的好友请求时才带计数）。 */
    public String friendsItemHtml(long pending) {
        return itemHtml(FRIENDS_ITEM_CLASS, "socialProfile.friends.link", "socialProfile.friends.title",
                "/wiki/特殊:好友", pending);
    }

    /** 「礼物（N）」条目（有还没看过的新礼物时才带计数）。 */
    public String giftsItemHtml(long unseen) {
        return itemHtml(GIFTS_ITEM_CLASS, "socialProfile.gifts.link", "socialProfile.gifts.title",
                "/wiki/特殊:礼物", unseen);
    }

    private String itemHtml(String className, String labelKey, String titleKey, String url, long count) {
        StringBuilder html = new StringBuilder();
        html.append("<li class=\"").append(className).append("\"><a href=\"").append(url)
                .append("\" title=\"").append(SocialProfileSupport.escape(support.message(titleKey)))
                .append("\"><span>").append(SocialProfileSupport.escape(support.message(labelKey)))
                .append("</span>");
        if (count > 0) {
            html.append("<span> (").append(count).append(")</span>");
        }
        return html.append("</a></li>").toString();
    }
}
