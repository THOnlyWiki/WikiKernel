package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个人资料与社交偏好 API（插件自己的 {@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/social-profile/profile}：保存资料字段（性别 / 生日 / 现居地 / 家乡 / 职业 /
 *     兴趣 / 个人网站 / 关于我）；需要登录；字段校验失败返回 400 + 消息键文案；</li>
 *     <li>{@code POST /api/social-profile/preferences}：保存社交偏好（是否显示生日 / 现居地、
 *     是否允许他人留言、是否显示礼物清单 / 勋章），对应「特殊:参数设置 → 社交资料」分区。</li>
 * </ul>
 * 字段名的 {@code ""}（空串）= 清空该字段；未提交的参数按空串处理（表单是整体提交）。
 */
@RestController
@RequestMapping("/api/social-profile")
public class SocialProfileApiController extends SocialProfileApiSupport {

    /** 资料字段名（与 {@code templates/special/edit-profile.html} 的表单一致）。 */
    public static final String PARAM_GENDER = "gender";
    public static final String PARAM_BIRTHDAY = "birthday";
    public static final String PARAM_LOCATION = "location";
    public static final String PARAM_HOMETOWN = "hometown";
    public static final String PARAM_OCCUPATION = "occupation";
    public static final String PARAM_INTERESTS = "interests";
    public static final String PARAM_WEBSITE = "website";
    public static final String PARAM_ABOUT = "about";

    private final ProfileService profiles;

    public SocialProfileApiController(ProfileService profiles, AccessCheck access, CurrentUser currentUser,
                                      SocialProfileSupport support, UserStore users) {
        super(access, currentUser, support, users);
        this.profiles = profiles;
    }

    /** 保存资料字段。 */
    @PostMapping("/profile")
    public Map<String, Object> saveProfile(
            @RequestParam(value = PARAM_GENDER, required = false) String gender,
            @RequestParam(value = PARAM_BIRTHDAY, required = false) String birthday,
            @RequestParam(value = PARAM_LOCATION, required = false) String location,
            @RequestParam(value = PARAM_HOMETOWN, required = false) String hometown,
            @RequestParam(value = PARAM_OCCUPATION, required = false) String occupation,
            @RequestParam(value = PARAM_INTERESTS, required = false) String interests,
            @RequestParam(value = PARAM_WEBSITE, required = false) String website,
            @RequestParam(value = PARAM_ABOUT, required = false) String about) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        if (!profiles.exists(userId())) {
            return error("socialProfile.error.userNotFound");
        }
        ProfileFields fields = new ProfileFields(gender, birthday, location, hometown,
                occupation, interests, website, about);
        try {
            ProfileFields saved = profiles.save(userId(), fields);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("profile", saved.toSettings());
            data.put("birthday", saved.birthday());
            return ok("socialProfile.profile.saved", data);
        } catch (ProfileService.ProfileException exception) {
            return fail(exception);
        }
    }

    /** 保存社交偏好（「特殊:参数设置 → 社交资料」分区与编辑资料页共用）。 */
    @PostMapping("/preferences")
    public Map<String, Object> savePreferences(
            @RequestParam(value = "showBirthday", required = false) String showBirthday,
            @RequestParam(value = "showLocation", required = false) String showLocation,
            @RequestParam(value = "allowBoard", required = false) String allowBoard,
            @RequestParam(value = "showGifts", required = false) String showGifts,
            @RequestParam(value = "showAwards", required = false) String showAwards) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        ProfileService.SocialPreferences preferences = new ProfileService.SocialPreferences(
                checked(showBirthday), checked(showLocation), checked(allowBoard),
                checked(showGifts), checked(showAwards));
        ProfileService.SocialPreferences saved = profiles.savePreferences(userId(), preferences);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("showBirthday", saved.showBirthday());
        data.put("showLocation", saved.showLocation());
        data.put("allowBoard", saved.allowBoard());
        data.put("showGifts", saved.showGifts());
        data.put("showAwards", saved.showAwards());
        return ok("socialProfile.preferences.saved", data);
    }

    /** 表单复选框：{@code on} / {@code 1} / {@code true} / {@code yes} 视为勾选。 */
    private static boolean checked(String value) {
        String normalized = Strings.trimToEmpty(value).toLowerCase(java.util.Locale.ROOT);
        return "on".equals(normalized) || "1".equals(normalized)
                || "true".equals(normalized) || "yes".equals(normalized);
    }
}
