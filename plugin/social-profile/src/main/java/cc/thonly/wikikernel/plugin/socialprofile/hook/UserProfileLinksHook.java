package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;

/**
 * 用户页资料卡的**头像**（仿 MediaWiki SocialProfile 的用户页钩子）。
 * <p>
 * core 的 {@code fragments/user-profile.html} 里只有三个槽位属于插件：头像、链接行、下方整宽面板。
 * 本类只接管头像：
 * <ul>
 *     <li>{@link #avatarUrl}：用户上传过的头像（{@code /avatars/<文件名>}）；没有上传时用默认头像，
 *     账号不存在时也不接管（core 用默认头像）；</li>
 *     <li>{@link #linksHtml}：**返回 null** —— 资料卡的链接行只留 core 自己的东西（贡献 / 用户页），
 *     社交入口（留言板 / 加好友 / 送礼物 / 发私信 / 编辑资料 / 上传头像 / 好友（N）/ 礼物（N）/
 *     积分排行）一律不在资料卡里重复，全部交给下方面板的操作按钮行
 *     （{@code SocialProfilePageDisplayHook} → {@code SocialProfileView.cardHtml} 的
 *     {@code .sp-card-actions}）与统计栏 {@code .sp-stats}，一处一个入口；</li>
 *     <li>{@link #avatarUploadUrl}：**返回 null** —— 「上传头像」的唯一位置是本人面板操作按钮行里的
 *     {@code .sp-button-avatar} 按钮（否则同一页会出现两个「上传头像」：一个在头像下、一个在按钮行里）。</li>
 * </ul>
 * 「用户交互」关闭时不贡献任何内容（core 侧资料卡也整体隐藏）。
 * <p>
 * 资料字段「关于」、统计栏与内嵌留言板由 {@code SocialProfilePageDisplayHook}
 * （{@code UserProfileHook.panelHtml}，资料卡下方、正文之前）贡献。
 */
public final class UserProfileLinksHook implements UserProfileHook {

    private final AvatarSupport avatars;
    private final SocialProfileSupport support;

    public UserProfileLinksHook(AvatarSupport avatars, SocialProfileSupport support) {
        this.avatars = avatars;
        this.support = support;
    }

    /**
     * 保留 5 参构造签名（插件启动代码与单元测试都在用）：社交服务由
     * {@code SocialProfilePageDisplayHook} 持有并渲染面板，本类不再需要它们，
     * 因此参数被忽略 —— 资料卡的链接行已不再展示任何社交入口。
     */
    public UserProfileLinksHook(AvatarSupport avatars, SocialProfileSupport support, BoardService board,
                                FriendsService friends, GiftService gifts) {
        this(avatars, support);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String avatarUrl(UserProfileContext context) {
        if (!support.enabled() || context == null || context.userId() <= 0) {
            return null;
        }
        return avatars.avatarUrl(context.userId());
    }

    @Override
    public String avatarUploadUrl(UserProfileContext context) {
        // 不再在头像下方给「上传头像」：本人面板的操作按钮行里已有 .sp-button-avatar 按钮，
        // core 模板也不再渲染这个入口（同一页只保留一处，见 SocialProfileView.cardHtml）
        return null;
    }

    @Override
    public String linksHtml(UserProfileContext context) {
        // 资料卡链接行只留 core 的「贡献 / 用户页」：社交入口（私信 / 留言板 / 好友 / 礼物 /
        // 积分排行 / 编辑资料 / 上传头像）在下方面板的操作按钮行与统计栏里，不在资料卡重复第二次
        return null;
    }
}
