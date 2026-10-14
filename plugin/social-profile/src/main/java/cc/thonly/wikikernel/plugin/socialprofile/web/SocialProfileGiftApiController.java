package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 礼物 API（插件自己的 {@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/social-profile/gift/send}（{@code user} 收礼人用户名、{@code gift} 礼物 id、
 *     {@code message} 可选附言）；</li>
 *     <li>{@code POST /api/social-profile/gift/delete}（{@code id} 礼物记录 id）——收礼人或送礼人可删。</li>
 * </ul>
 * 鉴权：需要登录；被封禁 403；礼物 id 必须在内置清单里（清单见 {@link GiftService#CATALOG}）；
 * 限流分类 {@code social-profile-gift}。
 */
@RestController
@RequestMapping("/api/social-profile/gift")
public class SocialProfileGiftApiController extends SocialProfileApiSupport {

    private final GiftService gifts;

    public SocialProfileGiftApiController(GiftService gifts, UserStore users, AccessCheck access,
                                          CurrentUser currentUser, SocialProfileSupport support) {
        super(access, currentUser, support, users);
        this.gifts = gifts;
    }

    /** 送一份礼物。 */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestParam("user") String user,
                                    @RequestParam("gift") String gift,
                                    @RequestParam(value = "message", required = false) String message) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            WikiUserGift saved = gifts.send(userId(), target, gift, message);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", saved.getId());
            data.put("giftId", saved.getGiftId());
            data.put("name", gifts.giftName(saved.getGiftId()));
            return ok("socialProfile.gifts.sent", data);
        } catch (GiftService.GiftException exception) {
            return fail(exception);
        }
    }

    /** 删除一份礼物（收礼人 / 送礼人）。 */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam("id") long id) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        try {
            gifts.delete(userId(), id);
            return ok("socialProfile.gifts.deleted", null);
        } catch (GiftService.GiftException exception) {
            return fail(exception);
        }
    }
}
