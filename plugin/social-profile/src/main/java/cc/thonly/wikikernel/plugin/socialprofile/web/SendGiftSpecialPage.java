package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:送礼物} / {@code Special:SendGift}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:SendGift}）：从**内置礼物清单**里选一个送给某用户，可附言。
 * <p>
 * {@code ?user=用户名} 预填收礼人；表单提交到 {@code POST /api/social-profile/gift/send}
 * （脚本 {@code /plugins/social-profile/gifts.js}）。
 */
public class SendGiftSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    public SendGiftSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                               BoardService board, FriendsService friends, GiftService gifts,
                               AvatarSupport avatars) {
        super(users, support, profiles, board, friends, gifts, avatars);
    }

    @Override
    public String name() {
        return "送礼物";
    }

    @Override
    public String displayName() {
        return display("socialProfile.gifts.send");
    }

    @Override
    public List<String> aliases() {
        return List.of("SendGift", "GiveGift");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/send-gift";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model)) {
            return;
        }
        String requested = request.parameters() == null ? "" : request.parameters().getOrDefault("user", "");
        model.put("giftTo", requested == null ? "" : requested.trim());
        model.put("giftCatalog", gifts.catalog());
        model.put("giftMessageLimit", GiftService.MESSAGE_LIMIT);
        model.put("giftsUrl", SocialProfileSupport.pagePath("特殊:礼物"));
        model.put("friendsUrl", SocialProfileSupport.pagePath("特殊:好友"));
        model.put("randomUsersUrl", SocialProfileSupport.pagePath("特殊:随机用户"));
    }
}
