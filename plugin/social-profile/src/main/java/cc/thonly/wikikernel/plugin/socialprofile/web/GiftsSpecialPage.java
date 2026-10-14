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
 * {@code 特殊:礼物} / {@code Special:ViewGifts}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:ViewGifts}）：查看自己**收到**与**送出**的礼物。
 * <ul>
 *     <li>默认「收到的礼物」（可删除，走 {@code POST /api/social-profile/gift/delete}）；</li>
 *     <li>{@code ?box=sent} 切换到自己送出的礼物；</li>
 *     <li>{@code ?user=某人} 看别人**公开**的收礼清单（只读，不含附言里的私密感操作——附言本身是公开的）。</li>
 * </ul>
 */
public class GiftsSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    public GiftsSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                            BoardService board, FriendsService friends, GiftService gifts,
                            AvatarSupport avatars) {
        super(users, support, profiles, board, friends, gifts, avatars);
    }

    @Override
    public String name() {
        return "礼物";
    }

    @Override
    public String displayName() {
        return display("socialProfile.gifts.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("Gifts", "ViewGifts", "MyGifts");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/gifts";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model)) {
            return;
        }
        model.put("giftsReceivedUrl", SocialProfileSupport.pagePath("特殊:礼物"));
        model.put("giftsSentUrl", SocialProfileSupport.pagePath("特殊:礼物?box=sent"));
        model.put("sendGiftUrl", SocialProfileSupport.pagePath("特殊:送礼物"));
        UserStore.UserRef target = target(request, model, true);
        if (target == null) {
            model.put("giftsTargetNotFound", Boolean.TRUE.equals(model.get("targetNotFound")));
            model.put("giftRows", List.of());
            return;
        }
        boolean self = currentUserId(request) == target.id();
        boolean sentBox = "sent".equalsIgnoreCase(request.parameters().getOrDefault("box", ""));
        if (sentBox && !self) {
            sentBox = false;
        }
        model.put("giftsSelf", self);
        model.put("giftsSentBox", sentBox);
        model.put("giftsOwner", target.username());
        List<WikiUserGift> rows = sentBox ? gifts.sent(target.id(), gifts.listLimit())
                : gifts.received(target.id(), gifts.listLimit());
        model.put("giftRows", toRows(rows, self, sentBox));
        Map<String, Long> byType = gifts.receivedByType(target.id());
        model.put("giftTypeRows", byType);
        model.put("giftTotal", gifts.receivedCount(target.id()));
    }

    /** 礼物行 → 模板模型（附言是纯文本，模板里用 th:text 转义）。 */
    private List<Map<String, Object>> toRows(List<WikiUserGift> rows, boolean self, boolean sentBox) {
        List<Map<String, Object>> result = new ArrayList<>();
        String pattern = support.message("format.dateTime", "yyyy-MM-dd HH:mm");
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException exception) {
            formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserGift row : rows) {
            ids.add(sentBox ? row.getToUserId() : row.getFromUserId());
        }
        Map<Long, String> names = users == null ? Map.of() : users.names(ids);
        for (WikiUserGift row : rows) {
            Long otherId = sentBox ? row.getToUserId() : row.getFromUserId();
            String other = otherId == null ? "" : names.getOrDefault(otherId, "#" + otherId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("giftId", row.getGiftId());
            view.put("giftName", gifts.giftName(row.getGiftId()));
            view.put("other", other.isEmpty() ? "#" + otherId : other);
            view.put("otherUrl", SocialProfileSupport.pagePath("用户:" + other));
            view.put("message", row.getMessage());
            view.put("timeText", row.getCreateAt() == null ? "" : row.getCreateAt().format(formatter));
            view.put("canDelete", self);
            result.add(view);
        }
        return result;
    }
}
