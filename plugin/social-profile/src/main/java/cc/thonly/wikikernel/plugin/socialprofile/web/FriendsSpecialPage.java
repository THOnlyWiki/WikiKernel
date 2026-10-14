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

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:好友} / {@code Special:Friends}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:Friends}）：好友列表、仇敌（foe）列表、收到 / 发出的好友请求。
 * <ul>
 *     <li>不带参数 / {@code ?user=} 自己：自己的好友管理页（含待处理请求的接受 / 拒绝）；</li>
 *     <li>{@code ?user=某人}：看某个用户的好友 / 仇敌列表（只读，不显示请求操作）；</li>
 *     <li>{@code ?tab=foes} / {@code ?tab=requests}：切换标签。</li>
 * </ul>
 * 所有变更走 {@code POST /api/social-profile/friend/*} 与 {@code /foe/*}（脚本
 * {@code /plugins/social-profile/friends.js}）。
 */
public class FriendsSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    /** 标签：好友列表。 */
    public static final String TAB_FRIENDS = "friends";
    /** 标签：仇敌列表。 */
    public static final String TAB_FOES = "foes";
    /** 标签：待处理的好友请求（只有本人能看）。 */
    public static final String TAB_REQUESTS = "requests";

    public FriendsSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                              BoardService board, FriendsService friends, GiftService gifts,
                              AvatarSupport avatars) {
        super(users, support, profiles, board, friends, gifts, avatars);
    }

    @Override
    public String name() {
        return "好友";
    }

    @Override
    public String displayName() {
        return display("socialProfile.friends.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("Friends", "MyFriends", "FriendList");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/friends";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model)) {
            return;
        }
        model.put("friendsUrl", SocialProfileSupport.pagePath("特殊:好友"));
        model.put("friendsFoesUrl", SocialProfileSupport.pagePath("特殊:好友?tab=foes"));
        model.put("friendsRequestsUrl", SocialProfileSupport.pagePath("特殊:好友?tab=requests"));
        model.put("boardUrl", SocialProfileSupport.pagePath("特殊:留言板"));
        model.put("giftsUrl", SocialProfileSupport.pagePath("特殊:礼物"));
        model.put("randomUsersUrl", SocialProfileSupport.pagePath("特殊:随机用户"));

        String requested = Strings.trimToEmpty(request.parameters().get("user"));
        UserStore.UserRef target = target(request, model, true);
        if (target == null) {
            model.put("friendsTargetNotFound", Boolean.TRUE.equals(model.get("targetNotFound")));
            model.put("friendRows", List.of());
            model.put("foeRows", List.of());
            model.put("requestRows", List.of());
            return;
        }
        boolean self = currentUserId(request) == target.id();
        String tab = Strings.trimToEmpty(request.parameters().get("tab")).toLowerCase(java.util.Locale.ROOT);
        if (!TAB_FOES.equals(tab) && !TAB_REQUESTS.equals(tab)) {
            tab = TAB_FRIENDS;
        }
        if (TAB_REQUESTS.equals(tab) && !self) {
            tab = TAB_FRIENDS;
        }
        model.put("friendsTab", tab);
        model.put("friendsSelf", self);
        model.put("friendsOwner", target.username());
        model.put("friendCount", friends.friendCount(target.id()));
        model.put("foeCount", (long) friends.foes(target.id()).size());
        model.put("friendRows", friends.toRows(friends.friends(target.id()), target.id()));
        model.put("foeRows", friends.toRows(friends.foes(target.id()), target.id()));
        model.put("requestRows", self
                ? friends.toRows(friends.incomingRequests(target.id()), target.id())
                : List.of());
        model.put("outgoingRequestRows", self
                ? friends.toRows(friends.outgoingRequests(target.id()), target.id())
                : List.of());
        model.put("friendsPending", friends.pendingCount(target.id()));
    }
}
