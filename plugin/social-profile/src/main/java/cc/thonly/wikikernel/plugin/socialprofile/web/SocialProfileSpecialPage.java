package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.util.Map;

/**
 * SocialProfile 特殊页的公共基类：开关注入、登录判断、按 {@code ?user=用户名} 定位目标用户
 * （找不到时退回当前用户），以及「目标用户 ID」这类公共取数。
 * <p>
 * 每个特殊页都自己做鉴权与错误提示（页面渲染前就能判断的权限直接给提示，不需要走 API）。
 */
abstract class SocialProfileSpecialPage {

    /** 功能关闭时放进模型的键（模板里显示「站点已关闭」提示）。 */
    protected static final String MODEL_DISABLED = "featureDisabled";
    /** 未登录标记。 */
    protected static final String MODEL_LOGIN = "loginRequired";

    protected final UserStore users;
    protected final SocialProfileSupport support;
    protected final ProfileService profiles;
    protected final BoardService board;
    protected final FriendsService friends;
    protected final GiftService gifts;
    protected final AvatarSupport avatars;

    protected SocialProfileSpecialPage(UserStore users, SocialProfileSupport support,
                                       ProfileService profiles, BoardService board, FriendsService friends,
                                       GiftService gifts, AvatarSupport avatars) {
        this.users = users;
        this.support = support;
        this.profiles = profiles;
        this.board = board;
        this.friends = friends;
        this.gifts = gifts;
        this.avatars = avatars;
    }

    /** 写入公共模型：开关状态与登录状态；返回 false 表示模板不该继续渲染业务内容。 */
    protected boolean prepare(SpecialPageRequest request, Map<String, Object> model) {
        return prepare(request, model, true);
    }

    /**
     * 与 {@link #prepare(SpecialPageRequest, Map)} 相同，但可指定**是否要求登录**：
     * <ul>
     *     <li>{@code requireLogin=true}（默认）：未登录返回 false（模板只显示登录提示）；</li>
     *     <li>{@code requireLogin=false}：只读页面（如留言板）让匿名用户也能看，
     *     模型里仍写 {@code loginRequired=true}，模板据此把输入框换成「登录后即可留言」提示。</li>
     * </ul>
     * 两种情况都会写 {@code featureDisabled}（功能关闭时始终返回 false）。
     */
    protected boolean prepare(SpecialPageRequest request, Map<String, Object> model, boolean requireLogin) {
        boolean disabled = !support.enabled();
        model.put(MODEL_DISABLED, disabled);
        WikiUser user = request == null ? null : request.user();
        boolean anonymous = user == null;
        model.put(MODEL_LOGIN, anonymous);
        if (disabled) {
            return false;
        }
        if (anonymous && requireLogin) {
            return false;
        }
        if (!anonymous) {
            model.put("currentUser", user);
        }
        return true;
    }

    /**
     * {@code ?user=用户名} → 目标用户。
     * <ul>
     *     <li>给了用户名但账号不存在 → 返回 null（{@code fallbackSelf} 时在模型里放 {@code targetNotFound}，页面显示「用户不存在」）；</li>
     *     <li>没给用户名 → 退回当前用户（自己的页面）。</li>
     * </ul>
     */
    protected UserStore.UserRef target(SpecialPageRequest request, Map<String, Object> model, boolean fallbackSelf) {
        WikiUser user = request == null ? null : request.user();
        String requested = Strings.trimToEmpty(request == null ? null : request.parameters().get("user"));
        boolean asked = !requested.isEmpty();
        UserStore.UserRef found = !asked || users == null ? null : users.byName(requested).orElse(null);
        if (found == null && !asked && fallbackSelf && user != null) {
            found = users == null ? null : users.byId(user.getId()).orElse(null);
        }
        if (found != null) {
            model.put("targetUser", found.username());
            model.put("targetUserId", found.id());
            model.put("targetUserUrl", SocialProfileSupport.pagePath("用户:" + found.username()));
            model.put("targetAvatarUrl", avatars == null
                    ? UserStore.DEFAULT_AVATAR_URL
                    : avatars.avatarUrl(found.id()));
            model.put("targetIsSelf", user != null && user.getId() != null && user.getId() == found.id());
        } else if (asked || fallbackSelf) {
            // 明确问了某个用户却没找到（或没给用户名且当前用户也没有账号）
            model.put("targetNotFound", true);
        }
        return found;
    }

    /** 当前用户 ID（未登录 0）。 */
    protected static long currentUserId(SpecialPageRequest request) {
        WikiUser user = request == null ? null : request.user();
        return user == null || user.getId() == null ? 0 : user.getId();
    }

    /** 当前用户名（未登录空串）。 */
    protected static String currentUserName(SpecialPageRequest request) {
        WikiUser user = request == null ? null : request.user();
        return user == null || user.getUsername() == null ? "" : user.getUsername();
    }

    /** 展示名（消息键缺失时显示键本身）。 */
    protected String display(String key) {
        return support.message(key);
    }
}
