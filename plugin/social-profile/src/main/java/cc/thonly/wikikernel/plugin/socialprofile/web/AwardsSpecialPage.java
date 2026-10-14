package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
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
 * {@code 特殊:奖励} / {@code Special:Awards}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:Awards}）：
 * <ul>
 *     <li>任何人都能看某个用户的勋章列表（{@code ?user=用户名}，默认看自己）；</li>
 *     <li>**管理员**（{@code 管理员} / {@code 行政员} 组，由 {@code common.service.AccessCheck} 判定）
 *     能授予 / 撤销某人的内置奖励（表单走 {@code POST /api/social-profile/award/grant} /
 *     {@code /revoke}）；</li>
 *     <li>奖励清单内置（{@link AwardService#CATALOG}），名称走消息键
 *     {@code socialProfile.award.<id>}。</li>
 * </ul>
 */
public class AwardsSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    /** 管理员的用户组名（与 API 层一致）。 */
    private static final Set<String> ADMIN_GROUPS = Set.of("管理员", "行政员", "sysop", "bureaucrat");

    private final AwardService awards;
    private final CurrentUser currentUser;

    public AwardsSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                             BoardService board, FriendsService friends, GiftService gifts,
                             AvatarSupport avatars, AwardService awards, CurrentUser currentUser) {
        super(users, support, profiles, board, friends, gifts, avatars);
        this.awards = awards;
        this.currentUser = currentUser;
    }

    @Override
    public String name() {
        return "奖励";
    }

    @Override
    public String displayName() {
        return display("socialProfile.awards.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("Awards", "Badges", "UserAwards");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/awards";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        if (!prepare(request, model)) {
            return;
        }
        boolean admin = isAdmin();
        model.put("awardsAdmin", admin);
        model.put("awardsCatalog", admin ? awards.catalog() : List.of());
        model.put("awardsCommentLimit", AwardService.COMMENT_LIMIT);
        model.put("awardsUrl", SocialProfileSupport.pagePath("特殊:奖励"));
        UserStore.UserRef target = target(request, model, true);
        if (target == null) {
            model.put("awardsTargetNotFound", Boolean.TRUE.equals(model.get("targetNotFound")));
            model.put("awardRows", List.of());
            return;
        }
        model.put("awardsOwner", target.username());
        model.put("awardsSelf", currentUserId(request) == target.id());
        List<WikiUserAward> rows = awards.of(target.id(), awards.listLimit());
        Set<Long> granterIds = new LinkedHashSet<>();
        for (WikiUserAward row : rows) {
            if (row.getGrantedBy() != null) {
                granterIds.add(row.getGrantedBy());
            }
        }
        Map<Long, String> names = users == null ? Map.of() : users.names(granterIds);
        model.put("awardRows", toRows(rows, names, admin));
    }

    /** 当前用户是不是管理员：认 core 的两个内置组名与英文别名（组集合来自 {@code CurrentUser}）。 */
    private boolean isAdmin() {
        if (currentUser == null) {
            return false;
        }
        for (String group : ADMIN_GROUPS) {
            if (currentUser.inGroup(group)) {
                return true;
            }
        }
        return false;
    }

    /** 管理员组名（供测试 / 文档引用）。 */
    public static Set<String> adminGroups() {
        return ADMIN_GROUPS;
    }

    /** 奖励行 → 模板模型。 */
    private List<Map<String, Object>> toRows(List<WikiUserAward> rows, Map<Long, String> names, boolean admin) {
        List<Map<String, Object>> result = new ArrayList<>();
        String pattern = support.message("format.dateTime", "yyyy-MM-dd HH:mm");
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException exception) {
            formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }
        for (WikiUserAward row : rows) {
            Long granterId = row.getGrantedBy();
            String granter = granterId == null ? "" : names.getOrDefault(granterId, "#" + granterId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("awardId", row.getAwardId());
            view.put("name", awards.awardName(row.getAwardId()));
            view.put("comment", Strings.trimToEmpty(row.getComment()));
            view.put("granter", granter.isEmpty() ? "#" + granterId : granter);
            view.put("granterUrl", SocialProfileSupport.pagePath("用户:" + granter));
            view.put("timeText", row.getCreateAt() == null ? "" : row.getCreateAt().format(formatter));
            view.put("canRevoke", admin);
            result.add(view);
        }
        return result;
    }
}
