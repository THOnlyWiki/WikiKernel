package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:上传头像} / {@code Special:UploadAvatar}（插件特殊页）：
 * 上传 / 替换自己的头像（用户页资料卡里只有本人能看到「上传头像」蓝链跳到这里）。
 * <p>
 * 匿名提示登录；「用户交互」关闭时显示「站点已关闭」提示；上传走插件自己的
 * {@code POST /api/user/avatar}（前端 upload-avatar 页复用 {@code /plugins/social-profile/avatar.js}）。
 */
public class UploadAvatarSpecialPage implements SpecialPage {

    private final AvatarSupport avatars;
    private final UserStore users;
    private final SocialProfileSupport support;

    public UploadAvatarSpecialPage(AvatarSupport avatars, UserStore users, SocialProfileSupport support) {
        this.avatars = avatars;
        this.users = users;
        this.support = support;
    }

    @Override
    public String name() {
        return "上传头像";
    }

    @Override
    public String displayName() {
        return support.message("special.uploadAvatar");
    }

    @Override
    public List<String> aliases() {
        return List.of("UploadAvatar", "Avatar", "MyAvatar");
    }

    @Override
    public String category() {
        return SpecialPageCategories.LOGIN;
    }

    @Override
    public String template() {
        return "special/upload-avatar";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        boolean featureDisabled = !support.enabled();
        model.put("featureDisabled", featureDisabled);
        WikiUser user = request.user();
        model.put("avatarLogin", user == null);
        if (featureDisabled || user == null) {
            return;
        }
        UserStore.UserRef ref = users.byId(user.getId()).orElse(null);
        String username = ref == null ? user.getUsername() : ref.username();
        model.put("avatarUserName", username);
        model.put("avatarUrl", ref == null ? UserStore.DEFAULT_AVATAR_URL : ref.avatarUrl(UserStore.DEFAULT_AVATAR_URL));
        model.put("avatarUserPageUrl", SocialProfileSupport.pagePath("用户:" + username));
    }
}
