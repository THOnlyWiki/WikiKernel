package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;

import java.util.List;

/**
 * 用户交互的前端资源（站点级注入：用户页资料卡、头像上传页、私信页、SocialProfile 的各个特殊页都需要）：
 * <ul>
 *     <li>{@code /plugins/social-profile/social-profile-base.css}（资料卡 / 头像上传 / 私信样式，原在 core 的 base.css）；</li>
 *     <li>{@code social-profile.css}：SocialProfile 的板面 / 好友 / 礼物 / 勋章 / 排行榜样式；</li>
 *     <li>{@code social-profile-mobile.css}：窄屏（≤600px）把私信 / 礼物表格变成卡片堆叠；</li>
 *     <li>{@code window.WIKI_CONFIG.userInteraction = true} 与脚本文案（{@code window.WIKI_MESSAGES}）；</li>
 *     <li>{@code avatar.js}（选中图片即上传）、{@code messages.js}（发私信 / 删除自己一侧）、
 *     {@code social-profile.js}（留言板 / 好友 / 礼物 / 勋章 / 编辑资料 / 社交偏好的表单提交）。</li>
 * </ul>
 */
public final class SocialProfileHeadProvider implements HtmlHeadProvider {

    private final SocialProfileSupport support;

    public SocialProfileHeadProvider(SocialProfileSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 640;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/social-profile/social-profile-base.css\">",
                "<link rel=\"stylesheet\" href=\"/plugins/social-profile/social-profile.css\">",
                "<link rel=\"stylesheet\" href=\"/plugins/social-profile/social-profile-mobile.css\">",
                "<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                        + "window.WIKI_CONFIG.userInteraction = true;"
                        + "window.WIKI_CONFIG.socialProfile = true;"
                        + "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "avatarSelect: " + SocialProfileSupport.json(
                        support.message("avatar.select")) + ","
                        + "avatarSaved: " + SocialProfileSupport.json(
                        support.message("avatar.saved")) + ","
                        + "messageDeleteConfirm: " + SocialProfileSupport.json(
                        support.message("messages.deleteConfirm")) + ","
                        + "spBoardPosted: " + SocialProfileSupport.json(
                        support.message("socialProfile.board.posted")) + ","
                        + "spBoardDeleteConfirm: " + SocialProfileSupport.json(
                        support.message("socialProfile.board.deleteConfirm")) + ","
                        + "spBoardDeleted: " + SocialProfileSupport.json(
                        support.message("socialProfile.board.deleted")) + ","
                        + "spProfileSaved: " + SocialProfileSupport.json(
                        support.message("socialProfile.profile.saved")) + ","
                        + "spPreferencesSaved: " + SocialProfileSupport.json(
                        support.message("socialProfile.preferences.saved")) + ","
                        + "spFriendRequested: " + SocialProfileSupport.json(
                        support.message("socialProfile.friends.requested")) + ","
                        + "spFriendAccepted: " + SocialProfileSupport.json(
                        support.message("socialProfile.friends.accepted")) + ","
                        + "spFriendRejected: " + SocialProfileSupport.json(
                        support.message("socialProfile.friends.rejected")) + ","
                        + "spFriendRemoved: " + SocialProfileSupport.json(
                        support.message("socialProfile.friends.removed")) + ","
                        + "spFriendRemoveConfirm: " + SocialProfileSupport.json(
                        support.message("socialProfile.friends.removeConfirm")) + ","
                        + "spFoeAdded: " + SocialProfileSupport.json(
                        support.message("socialProfile.foes.added")) + ","
                        + "spFoeRemoved: " + SocialProfileSupport.json(
                        support.message("socialProfile.foes.removed")) + ","
                        + "spGiftSent: " + SocialProfileSupport.json(
                        support.message("socialProfile.gifts.sent")) + ","
                        + "spGiftDeleted: " + SocialProfileSupport.json(
                        support.message("socialProfile.gifts.deleted")) + ","
                        + "spGiftDeleteConfirm: " + SocialProfileSupport.json(
                        support.message("socialProfile.gifts.deleteConfirm")) + ","
                        + "spAwardGranted: " + SocialProfileSupport.json(
                        support.message("socialProfile.awards.granted")) + ","
                        + "spAwardRevoked: " + SocialProfileSupport.json(
                        support.message("socialProfile.awards.revoked")) + ","
                        + "spAwardRevokeConfirm: " + SocialProfileSupport.json(
                        support.message("socialProfile.awards.revokeConfirm")) + ","
                        + "spOperationFailed: " + SocialProfileSupport.json(
                        support.message("socialProfile.error.failed"))
                        + "});</script>",
                "<script defer src=\"/plugins/social-profile/avatar.js\"></script>",
                "<script defer src=\"/plugins/social-profile/messages.js\"></script>",
                "<script defer src=\"/plugins/social-profile/social-profile.js\"></script>");
    }
}
