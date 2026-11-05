package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.service.*;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import java.util.List;
import java.util.Map;

/** 原版 Special:RemoveAvatar：本人可删除自己的头像，管理权限可删除他人头像。 */
public final class RemoveAvatarSpecialPage implements SpecialPage {
    private final UserStore users;
    private final AccessCheck access;
    private final CurrentUser current;
    private final SocialProfileSupport support;

    public RemoveAvatarSpecialPage(UserStore users, AccessCheck access, CurrentUser current, SocialProfileSupport support) {
        this.users = users; this.access = access; this.current = current; this.support = support;
    }
    @Override public String name() { return "删除头像"; }
    @Override public List<String> aliases() { return List.of("RemoveAvatar"); }
    @Override public String displayName() { return support.message("special.removeAvatar"); }
    @Override public String category() { return SpecialPageCategories.LOGIN; }
    @Override public String template() { return "special/remove-avatar"; }
    @Override public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("featureDisabled", !support.enabled());
        model.put("avatarLogin", request.user() == null);
        if (!support.enabled() || request.user() == null) { return; }
        String name = request.parameters().getOrDefault("user", request.user().getUsername());
        var user = users.byName(name).orElse(null);
        boolean allowed = user != null && (user.id() == request.user().getId()
                || access.can(AccessAction.PROTECT, "特殊:删除头像", current.viewer()));
        model.put("avatarRemoveForbidden", !allowed);
        if (allowed) {
            model.put("avatarUserName", user.username());
            model.put("avatarUrl", user.avatarUrl(users.defaultAvatarUrl()));
        }
    }
}
