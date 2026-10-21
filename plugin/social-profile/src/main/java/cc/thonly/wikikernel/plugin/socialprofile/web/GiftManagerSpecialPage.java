package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:GiftManager} / {@code Special:GiftManager}：
 * 登录用户可以创建自定义礼物；创建者本人和管理员可以停用/重新启用自己创建的礼物。
 * 内置礼物不可修改，自定义礼物停用后不再出现在送礼清单，但历史礼物仍能显示名称。
 */
public class GiftManagerSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    private final CurrentUser currentUser;

    public GiftManagerSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                                  BoardService board, FriendsService friends, GiftService gifts,
                                  AvatarSupport avatars, CurrentUser currentUser) {
        super(users, support, profiles, board, friends, gifts, avatars);
        this.currentUser = currentUser;
    }

    @Override
    public String name() {
        return "GiftManager";
    }

    @Override
    public String displayName() {
        return display("socialProfile.giftManager.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("礼物管理", "礼物管理器", "礼物管理員");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/gift-manager";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model, false)) {
            return;
        }
        long actorId = currentUserId(request);
        boolean admin = isAdmin();
        model.put("giftManagerLoginRequired", actorId <= 0);
        model.put("giftManagerAdmin", admin);
        model.put("giftManagerRows", actorId <= 0 ? List.of() : gifts.managerRows(actorId, admin));
        model.put("giftNameLimit", GiftService.NAME_LIMIT);
        model.put("giftDescriptionLimit", GiftService.DESCRIPTION_LIMIT);
        model.put("giftIconLimit", GiftService.ICON_LIMIT);
        model.put("giftsUrl", SocialProfileSupport.pagePath("特殊:礼物"));
        model.put("sendGiftUrl", SocialProfileSupport.pagePath("特殊:送礼物"));
        model.put("giftManagerUrl", SocialProfileSupport.pagePath("特殊:GiftManager"));
    }

    private boolean isAdmin() {
        return currentUser != null && (currentUser.inGroup("管理员")
                || currentUser.inGroup("行政员")
                || currentUser.inGroup("sysop") || currentUser.inGroup("bureaucrat"));
    }
}
