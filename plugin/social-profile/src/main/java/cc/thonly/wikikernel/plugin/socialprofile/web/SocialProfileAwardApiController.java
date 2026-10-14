package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 奖励 / 勋章 API（插件自己的 {@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/social-profile/award/grant}（{@code user} 用户名、{@code award} 奖励 id、
 *     {@code comment} 可选说明）——**仅管理员**（{@code 管理员} / {@code 行政员} 组，由
 *     {@code common.service.AccessCheck} 提供的用户组判定）；同一种奖励不重复授予；</li>
 *     <li>{@code POST /api/social-profile/award/revoke}（{@code id} 授予记录 id）——仅管理员。</li>
 * </ul>
 * 奖励清单见 {@link AwardService#CATALOG}（名称走消息键 {@code socialProfile.award.<id>}）。
 */
@RestController
@RequestMapping("/api/social-profile/award")
public class SocialProfileAwardApiController extends SocialProfileApiSupport {

    private final AwardService awards;

    public SocialProfileAwardApiController(AwardService awards, UserStore users, AccessCheck access,
                                           CurrentUser currentUser, SocialProfileSupport support) {
        super(access, currentUser, support, users);
        this.awards = awards;
    }

    /** 授予奖励（管理员）。 */
    @PostMapping("/grant")
    public Map<String, Object> grant(@RequestParam("user") String user,
                                     @RequestParam("award") String award,
                                     @RequestParam(value = "comment", required = false) String comment) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        long target = userIdOf(user);
        if (target <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            WikiUserAward saved = awards.grant(userId(), isAdmin(), target, award, comment);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", saved.getId());
            data.put("awardId", saved.getAwardId());
            data.put("name", awards.awardName(saved.getAwardId()));
            return ok("socialProfile.awards.granted", data);
        } catch (AwardService.AwardException exception) {
            return fail(exception);
        }
    }

    /** 撤销奖励（管理员）。 */
    @PostMapping("/revoke")
    public Map<String, Object> revoke(@RequestParam("id") long id) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        try {
            awards.revoke(userId(), isAdmin(), id);
            return ok("socialProfile.awards.revoked", null);
        } catch (AwardService.AwardException exception) {
            return fail(exception);
        }
    }
}
