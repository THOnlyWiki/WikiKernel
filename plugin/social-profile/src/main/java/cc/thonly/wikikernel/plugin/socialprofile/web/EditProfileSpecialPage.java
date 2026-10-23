package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:编辑资料} / {@code Special:EditProfile}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:EditProfile}）：编辑自己的资料字段（性别 / 生日 / 现居地 / 家乡 / 职业 / 兴趣 /
 * 个人网站 / 关于我）与社交偏好（是否显示生日 / 现居地、是否允许他人留言、是否显示礼物清单 / 勋章）。
 * <p>
 * 表单提交到插件自己的 {@code POST /api/social-profile/profile}（脚本
 * {@code /plugins/social-profile/edit-profile.js}）；头像仍在 {@code 特殊:上传头像}。
 */
public class EditProfileSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    public EditProfileSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                                  BoardService board, FriendsService friends, GiftService gifts,
                                  AvatarSupport avatars) {
        super(users, support, profiles, board, friends, gifts, avatars);
    }

    @Override
    public String name() {
        return "编辑资料";
    }

    @Override
    public String displayName() {
        return display("socialProfile.editProfile");
    }

    @Override
    public List<String> aliases() {
        return List.of("EditProfile", "MyProfile", "SocialProfile");
    }

    @Override
    public String category() {
        return SpecialPageCategories.LOGIN;
    }

    @Override
    public String template() {
        return "special/edit-profile";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model)) {
            return;
        }
        long userId = currentUserId(request);
        ProfileFields fields = profiles.of(userId);
        ProfileService.SocialPreferences preferences = profiles.preferences(userId);
        model.put("profile", fields);
        model.put("preferences", preferences);
        model.put("genders", genderOptions(fields.gender()));
        model.put("avatarUrl", avatars == null
                ? (users == null ? UserStore.DEFAULT_AVATAR_URL : users.defaultAvatarUrl())
                : avatars.avatarUrl(userId));
        model.put("userPageUrl", SocialProfileSupport.pagePath("用户:" + currentUserName(request)));
        model.put("boardUrl", SocialProfileSupport.pagePath("特殊:留言板"));
        model.put("friendsUrl", SocialProfileSupport.pagePath("特殊:好友"));
        model.put("giftsUrl", SocialProfileSupport.pagePath("特殊:礼物"));
        model.put("topUsersUrl", SocialProfileSupport.pagePath("特殊:积分排行"));
        model.put("awardsUrl", SocialProfileSupport.pagePath("特殊:奖励"));
        model.put("uploadAvatarUrl", SocialProfileSupport.pagePath("特殊:上传头像"));
    }

    /** 性别下拉框（第一项「未指定」= 空串）。 */
    private List<Map<String, Object>> genderOptions(String current) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String gender : ProfileService.GENDERS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", gender);
            row.put("label", gender.isEmpty()
                    ? display("socialProfile.gender.unspecified")
                    : display("socialProfile.gender." + gender));
            row.put("selected", gender.equalsIgnoreCase(current == null ? "" : current));
            options.add(row);
        }
        return options;
    }
}
