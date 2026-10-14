package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 好友 / 仇敌 API（插件自己的 {@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/social-profile/friend/request}（{@code user} 用户名）——发送好友请求（幂等；
 *     对方先发过请求时直接互相接受）；</li>
 *     <li>{@code POST /api/social-profile/friend/accept} / {@code /reject}（{@code id} 关系 id）——接受 / 拒绝；</li>
 *     <li>{@code POST /api/social-profile/friend/remove}（{@code user}）——取消请求 / 解除好友（双向）；</li>
 *     <li>{@code POST /api/social-profile/foe/add} / {@code /remove}（{@code user}）——仇敌的加 / 移出（单向）。</li>
 * </ul>
 * 鉴权：全部需要登录；被封禁 403；限流分类 {@code social-profile-friend}。
 */
@RestController
@RequestMapping("/api/social-profile")
public class SocialProfileFriendApiController extends SocialProfileApiSupport {

    private final FriendsService friends;

    public SocialProfileFriendApiController(FriendsService friends, UserStore users, AccessCheck access,
                                            CurrentUser currentUser, SocialProfileSupport support) {
        super(access, currentUser, support, users);
        this.friends = friends;
    }

    /** 发送好友请求。 */
    @PostMapping("/friend/request")
    public Map<String, Object> requestFriend(@RequestParam("user") String user) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            WikiUserRelation relation = friends.requestFriend(userId(), target);
            return ok("socialProfile.friends.requested", relation(relation));
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 接受好友请求（只有收件人能接受）。 */
    @PostMapping("/friend/accept")
    public Map<String, Object> accept(@RequestParam("id") long id) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        try {
            WikiUserRelation relation = friends.accept(userId(), id);
            return ok("socialProfile.friends.accepted", relation(relation));
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 拒绝好友请求。 */
    @PostMapping("/friend/reject")
    public Map<String, Object> reject(@RequestParam("id") long id) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        try {
            friends.reject(userId(), id);
            return ok("socialProfile.friends.rejected", null);
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 取消好友请求 / 解除好友（双向删除）。 */
    @PostMapping("/friend/remove")
    public Map<String, Object> remove(@RequestParam("user") String user) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            friends.removeFriend(userId(), target);
            return ok("socialProfile.friends.removed", null);
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 把某人加为仇敌（单向，立即生效）。 */
    @PostMapping("/foe/add")
    public Map<String, Object> addFoe(@RequestParam("user") String user) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            WikiUserRelation relation = friends.addFoe(userId(), target);
            return ok("socialProfile.foes.added", relation(relation));
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 移出仇敌列表。 */
    @PostMapping("/foe/remove")
    public Map<String, Object> removeFoe(@RequestParam("user") String user) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            friends.removeFoe(userId(), target);
            return ok("socialProfile.foes.removed", null);
        } catch (FriendsService.FriendException exception) {
            return fail(exception);
        }
    }

    /** 关系行 → 响应里的 Map。 */
    private static Map<String, Object> relation(WikiUserRelation relation) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (relation == null) {
            return view;
        }
        view.put("id", relation.getId());
        view.put("fromUserId", relation.getFromUserId());
        view.put("toUserId", relation.getToUserId());
        view.put("type", relation.getRelationType());
        view.put("status", relation.getStatus());
        return view;
    }
}
