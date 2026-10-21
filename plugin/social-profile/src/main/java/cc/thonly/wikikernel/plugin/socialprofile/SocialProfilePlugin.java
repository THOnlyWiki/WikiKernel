package cc.thonly.wikikernel.plugin.socialprofile;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiGift;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import cc.thonly.wikikernel.plugin.socialprofile.hook.PointsViewHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.SocialPreferencesSection;
import cc.thonly.wikikernel.plugin.socialprofile.hook.SocialProfileHeadProvider;
import cc.thonly.wikikernel.plugin.socialprofile.hook.SocialProfilePageDisplayHook;
import cc.thonly.wikikernel.plugin.socialprofile.hook.UserProfileLinksHook;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsRulesProvider;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import cc.thonly.wikikernel.plugin.socialprofile.service.SocialProfileView;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserAwardStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserBoardStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserGiftStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserRelationStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserStatsStore;
import cc.thonly.wikikernel.plugin.socialprofile.web.AwardsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.EditProfileSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.FriendsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.GiftsSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.GiftManagerSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.MessagesSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.RandomUsersSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.SendGiftSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.TopUsersSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.UploadAvatarSpecialPage;
import cc.thonly.wikikernel.plugin.socialprofile.web.UserBoardSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.List;

/**
 * 用户交互（SocialProfile）插件：**用户页头像上传 + 用户间私信**，外加一套仿 MediaWiki
 * SocialProfile 扩展的社交功能。
 *
 * <h2>功能清单</h2>
 * <ul>
 *     <li><b>头像</b>（原有）：{@code 特殊:上传头像}、{@code POST /api/user/avatar}、{@code GET /avatars/<文件名>}；</li>
 *     <li><b>私信</b>（原有）：{@code 特殊:私信}、{@code /api/messages/*}、顶栏「私信（N）」；</li>
 *     <li><b>资料字段 + 编辑资料</b>：{@code 特殊:编辑资料}、{@code POST /api/social-profile/profile}
 *     （性别 / 生日（只存月-日）/ 现居地 / 家乡 / 职业 / 兴趣 / 个人网站 / 关于我）；</li>
 *     <li><b>用户留言板</b>：{@code 特殊:留言板}、{@code /api/social-profile/board/*}、用户页资料卡链接 +
 *     资料卡下方的内嵌留言板（最近 N 条 + 回复缩进 + 内联输入框 / 删除，`UserProfileHook.panelHtml`）；</li>
 *     <li><b>好友 / 仇敌</b>：{@code 特殊:好友}、{@code /api/social-profile/friend/*} 与 {@code /foe/*}、
 *     顶栏「好友（N）」（待处理请求数）；</li>
 *     <li><b>礼物</b>：{@code 特殊:送礼物} / {@code 特殊:礼物}、{@code /api/social-profile/gift/*}、
 *     顶栏「礼物（N）」（未查看的新礼物）；</li>
 *     <li><b>积分 / 等级 / 排行榜</b>：{@code 特殊:积分排行}、给分入口见 {@code PointsService} 与
 *     {@link PointsViewHook}（配置键 {@code socialProfile.points.*}）；</li>
 *     <li><b>奖励 / 勋章</b>：{@code 特殊:奖励}、{@code /api/social-profile/award/*}（仅管理员授予 / 撤销）；</li>
 *     <li><b>随机用户</b>：{@code 特殊:随机用户}（随机抽有头像的用户）；</li>
 *     <li><b>社交偏好</b>：参数设置里的「社交资料」分区（{@link SocialPreferencesSection}），
 *     键前缀 {@code socialProfile.pref.}，存在 {@code common.service.UserSettings}。</li>
 * </ul>
 *
 * <h2>与迁移前 core 的关系</h2>
 * core 不再内置头像与私信（{@code AvatarService} / {@code MessageService} / 两个特殊页 / 两个 API /
 * 前端脚本 / 样式 / 文案全部移除），只保留站点设置里的功能开关（{@code features.social-profile}，
 * 兼容旧键 {@code features.user-interaction}，见 {@link SocialProfileSupport#LEGACY_ENABLED_KEY}）
 * 与一个渲染槽位钩子（{@code UserProfileHook}）。
 * SocialProfile 自己的数据用六张表（{@code wiki_user_board} / {@code wiki_user_relation} /
 * {@code wiki_user_gift} / {@code wiki_user_stats} / {@code wiki_user_award} / {@code wiki_message}），
 * 资料字段与社交偏好落在用户偏好表；头像文件名仍记在 {@code wiki_user.avatar}。
 * <p>
 * <b>表与数据访问属于插件自己</b>：实体在 {@code plugin.socialprofile.data}、表结构由
 * {@link #schemaEntities()} 声明（core 的 Auto-DDL 建表 / 补列）、数据访问是插件里的 JDBC
 * {@code @Service}（走 {@code common.service.PluginDatabase}）；头像的校验 / 落盘 / URL 规则也在
 * 插件里（{@code service.AvatarStoreJdbc}）。通用用户数据仍走 core 的桥
 * （{@code common.service.UserStore} / {@code UserSettings} 等）——插件不碰 core 的 Mapper / 实体。
 */
public class SocialProfilePlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）：{@code features.social-profile}。 */
    public static final String ENABLED_KEY = SocialProfileSupport.ENABLED_KEY;

    @Autowired
    private AvatarStore avatarStore;

    @Autowired
    private MessageStore messageStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserSettings userSettings;

    @Autowired
    private UserBoardStore userBoardStore;

    @Autowired
    private UserRelationStore userRelationStore;

    @Autowired
    private UserGiftStore userGiftStore;

    @Autowired
    private UserStatsStore userStatsStore;

    @Autowired
    private UserAwardStore userAwardStore;

    @Autowired
    private AccessCheck accessCheck;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private WikiText wikiText;

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    /** 头像规则（Spring Bean：API / 特殊页 / 资料卡钩子共用同一个实例）。 */
    @Autowired
    private AvatarSupport avatars;

    /** 私信中心（Spring Bean：API / 特殊页 / 顶栏钩子共用同一个实例）。 */
    @Autowired
    private MessageCenter messages;

    /** 开关与文案（Spring Bean：API / 特殊页 / 钩子共用同一个实例）。 */
    @Autowired
    private SocialProfileSupport support;

    /** 资料字段服务。 */
    @Autowired
    private ProfileService profiles;

    /** 留言板规则。 */
    @Autowired
    private BoardService board;

    /** 好友 / 仇敌规则。 */
    @Autowired
    private FriendsService friends;

    /** 礼物规则。 */
    @Autowired
    private GiftService gifts;

    /** 积分 / 等级。 */
    @Autowired
    private PointsService points;

    /** 给分规则（站点设置 / 插件配置读一次配置的共享组件）。 */
    @Autowired
    private PointsRulesProvider pointsRules;

    /** 奖励 / 勋章。 */
    @Autowired
    private AwardService awards;

    /** SocialProfile 的 HTML 片段渲染（资料字段 / 礼物 / 勋章 / 留言板面板）。 */
    @Autowired
    private SocialProfileView socialProfileView;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("social-profile", "SocialProfile", "1.0.0",
                "Avatar upload on user pages plus private messages between users"
                        + " (Special:UploadAvatar + Special:Messages)");
    }

    /**
     * 插件自己的六张表（{@code wiki_user_board} / {@code wiki_user_relation} / {@code wiki_user_gift} /
     * {@code wiki_user_stats} / {@code wiki_user_award} / {@code wiki_message}）：core 在 {@code onStart}
     * 之前按与 core 相同的 Auto-DDL 规则建表 / 补列（SQLite / MySQL / H2），数据访问见
     * {@code service/*StoreJdbc}（走 JDBC 桥 {@code common.service.PluginDatabase}）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiUserBoard.class, WikiUserRelation.class, WikiUserGift.class,
                WikiGift.class, WikiUserStats.class, WikiUserAward.class, WikiMessage.class);
    }

    @Override
    public void onStart(PluginContext context) {

        context.register(MessageBundle.class, new SocialProfileMessages());
        // 原有能力：头像上传 + 私信
        context.register(SpecialPage.class, new UploadAvatarSpecialPage(avatars, userStore, support));
        context.register(SpecialPage.class,
                new MessagesSpecialPage(messages, userStore, wikiText, support));
        context.register(HtmlHeadProvider.class, new SocialProfileHeadProvider(support));
        // SocialProfile：资料 / 留言板 / 好友 / 礼物 / 积分排行 / 奖励 / 随机用户
        context.register(SpecialPage.class, new EditProfileSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars));
        context.register(SpecialPage.class, new UserBoardSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars, wikiText));
        context.register(SpecialPage.class, new FriendsSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars));
        context.register(SpecialPage.class, new SendGiftSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars));
        context.register(SpecialPage.class, new GiftsSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars));
        context.register(SpecialPage.class, new GiftManagerSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars, currentUser));
        context.register(SpecialPage.class, new TopUsersSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars, points, userStatsStore, pointsRules));
        context.register(SpecialPage.class, new AwardsSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars, awards, currentUser));
        context.register(SpecialPage.class, new RandomUsersSpecialPage(userStore, support, profiles, board,
                friends, gifts, avatars));
        // 用户页资料卡（头像 + 私信 / 留言板 / 好友 / 礼物 / 积分 / 编辑资料链接）
        context.register(UserProfileHook.class,
                new UserProfileLinksHook(avatars, support, board, friends, gifts));
        // 用户页资料卡**下方、正文之前**的 SocialProfile 面板（操作按钮 / 关系状态 / 关于字段 /
        // 统计栏 / 内嵌留言板）：走 UserProfileHook.panelHtml，而不是 PageDisplayHook.afterContent
        // （后者会把整块堆在正文之后，未创建的用户页也拿不到）
        context.register(UserProfileHook.class, new SocialProfilePageDisplayHook(userStore, support, profiles,
                board, friends, gifts, awards, points, avatars, socialProfileView));
        // 积分：浏览词条给分（core 没有「编辑完成」事件，见 PointsService 的说明）
        context.register(PageViewHook.class, new PointsViewHook(points));
        // 参数设置里的「社交资料」分区（社交偏好）
        context.register(PreferenceSection.class, new SocialPreferencesSection(profiles, support));
        // API（avatar 上传 / 私信 / 资料 / 留言板 / 好友 / 礼物 / 奖励）与头像文件服务由插件里的
        // Spring 注解（@RestController）提供，core 的 PluginWebRegistrar 会挂到 MVC 上

        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用用户交互：头像上传 / 私信 / 资料编辑 / 留言板 / 好友 / 礼物 / 积分排行 / 奖励 / 随机用户"
                + "（/plugins/social-profile/，特殊页见 特殊:编辑资料 等）"
                : "用户交互已关闭（features.social-profile=false，兼容旧键 features.user-interaction=false）："
                + "不显示资料卡与各入口，两个原有特殊页与所有接口提示不可用");
    }
}
