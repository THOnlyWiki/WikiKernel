package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.UserProfileHook;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AwardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.PointsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import cc.thonly.wikikernel.plugin.socialprofile.service.SocialProfileView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用户页的 SocialProfile 版式（{@code 用户:X} 页面，像素级对齐 MediaWiki SocialProfile 原版）：
 * <ul>
 *     <li>{@link #profileTopHtml}：{@code #profile-top} 资料卡顶部（75×75 头像、大号用户名、
 *     红色积分徽章、等级名、{@code .profile-actions} 操作链接）——由 core 的
 *     {@code UserProfileHook.profileTopHtml} 槽位**取代**内置资料卡；</li>
 *     <li>{@link #panelHtml}：资料卡下方、正文之前的左右两栏（{@code #user-page-left} 统计 / 好友 /
 *     仇敌 / 礼物 / 勋章；{@code #user-page-right} 个人信息 / 用户墙）。</li>
 * </ul>
 * 数据一次组装（{@link #buildView}）：账号、资料字段、统计、好友 / 仇敌列表、留言与作者名 / 头像
 * 都用批量桥调用取回，避免按条查库。未创建的用户页（账号不存在）仍渲染顶部（贡献链接）与留言墙。
 */
public final class SocialProfilePageDisplayHook implements UserProfileHook {

    private final UserStore users;
    private final SocialProfileSupport support;
    private final ProfileService profiles;
    private final BoardService board;
    private final FriendsService friends;
    private final GiftService gifts;
    private final AwardService awards;
    private final PointsService points;
    private final AvatarSupport avatars;
    private final SocialProfileView view;

    public SocialProfilePageDisplayHook(UserStore users, SocialProfileSupport support,
                                        ProfileService profiles, BoardService board, FriendsService friends,
                                        GiftService gifts, AwardService awards, PointsService points,
                                        AvatarSupport avatars, SocialProfileView view) {
        this.users = users;
        this.support = support;
        this.profiles = profiles;
        this.board = board;
        this.friends = friends;
        this.gifts = gifts;
        this.awards = awards;
        this.points = points;
        this.avatars = avatars;
        this.view = view;
    }

    /** 兼容旧构造签名（没有积分 / 头像服务：资料卡顶部不显示积分与好友列表）。 */
    public SocialProfilePageDisplayHook(UserStore users, SocialProfileSupport support,
                                        ProfileService profiles, BoardService board, FriendsService friends,
                                        GiftService gifts, AwardService awards, SocialProfileView view) {
        this(users, support, profiles, board, friends, gifts, awards, null, null, view);
    }

    @Override
    public int order() {
        return 300;
    }

    /** 资料卡整体闸门：功能关闭（{@code features.social-profile=false}，含旧键回退）时不渲染资料卡。 */
    @Override
    public boolean enabled() {
        return support.enabled();
    }

    @Override
    public String profileTopHtml(UserProfileContext context) {
        if (!support.enabled() || context == null) {
            return "";
        }
        SocialProfileView.View model = buildView(context);
        return model == null ? "" : view.profileTop(model);
    }

    @Override
    public String panelHtml(UserProfileContext context) {
        if (!support.enabled() || context == null) {
            return "";
        }
        SocialProfileView.View model = buildView(context);
        return model == null ? "" : view.userPanel(model);
    }

    /** 一次把用户页需要的数据组装成 {@link SocialProfileView.View}（账号不存在也返回，用于留言墙）。 */
    private SocialProfileView.View buildView(UserProfileContext context) {
        String username = context.username();
        if (Strings.isBlank(username)) {
            return null;
        }
        // 只认「用户:X」本身（用户子页 / 讨论页不挂——避免同一份内容出现在多个页面）
        if (username.contains("/")) {
            return null;
        }
        Viewer viewer = context.viewer();
        UserStore.UserRef account = users == null ? null : users.byName(username).orElse(null);
        long userId = account == null ? 0 : account.id();
        boolean loggedIn = viewer != null && viewer.loggedIn();
        boolean self = loggedIn && userId > 0 && viewer.id() == userId;
        boolean canPost = loggedIn && userId > 0 && !self && board.allowsBoard(userId);
        List<WikiUserBoard> rows = userId <= 0 ? List.of() : board.recent(userId, BOARD_ROWS_LIMIT);
        Map<Long, String> authorNames = authorNames(rows);
        Map<Long, String> authorAvatars = authorAvatars(rows);

        long pointsValue = 0;
        String levelName = "";
        if (userId > 0 && points != null) {
            Optional<WikiUserStats> stats = points.of(userId);
            pointsValue = stats.map(WikiUserStats::getPoints).orElse(0L);
            levelName = points.levelName(stats.map(WikiUserStats::getLevel).orElse(1));
        }

        return new SocialProfileView.View(
                SocialProfileView.SCOPE_USER_PAGE,
                userId,
                account == null ? username : account.username(),
                self,
                canPost,
                userId <= 0 ? ProfileFields.empty() : profiles.of(userId),
                userId <= 0 ? ProfileService.SocialPreferences.DEFAULT : profiles.preferences(userId),
                userId <= 0 ? 0 : friends.friendCount(userId),
                userId <= 0 ? Map.of() : gifts.receivedByType(userId),
                userId <= 0 ? 0 : gifts.receivedCount(userId),
                userId <= 0 ? List.of() : awards.awardIdsOf(userId),
                userId <= 0 ? 0 : board.count(userId),
                rows,
                SocialProfileSupport.pagePath("特殊:留言板?user=" + username),
                authorNames,
                relationState(viewer, userId, self),
                incomingFoe(viewer, userId, self),
                userId <= 0 ? 0 : friends.pendingCount(userId),
                userId <= 0 ? UserStore.DEFAULT_AVATAR_URL : avatarUrl(userId),
                pointsValue,
                levelName,
                userId <= 0 ? List.of() : people(friends.friends(userId), userId),
                userId <= 0 ? List.of() : people(friends.foes(userId), userId),
                loggedIn,
                authorAvatars);
    }

    /** 留言墙内嵌显示条数（与 {@code SocialProfileView} 的 {@code BOARD_EMBED_LIMIT} 一致：10）。 */
    private static final int BOARD_ROWS_LIMIT = 10;

    /** 当前用户的资料卡头像 URL（空 / 未注入头像服务时回退默认头像）。 */
    private String avatarUrl(long userId) {
        if (avatars == null) {
            return UserStore.DEFAULT_AVATAR_URL;
        }
        String url = avatars.avatarUrl(userId);
        return Strings.isBlank(url) ? UserStore.DEFAULT_AVATAR_URL : url;
    }

    /** 好友 / 仇敌列表 → 头像列表（对方那一侧的 id / 用户名 / 头像，批量查名与头像）。 */
    private List<SocialProfileView.View.PersonRef> people(List<WikiUserRelation> relations, long ownerId) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserRelation relation : relations) {
            long other = otherId(relation, ownerId);
            if (other > 0) {
                ids.add(other);
            }
        }
        Map<Long, String> names = ids.isEmpty() ? Map.of() : users.names(ids);
        Map<Long, String> avatarFiles = ids.isEmpty() ? Map.of() : users.avatars(ids);
        List<SocialProfileView.View.PersonRef> people = new ArrayList<>();
        for (WikiUserRelation relation : relations) {
            long other = otherId(relation, ownerId);
            if (other <= 0) {
                continue;
            }
            String name = names.getOrDefault(other, "#" + other);
            people.add(new SocialProfileView.View.PersonRef(other, name, avatarUrl(avatarFiles.get(other))));
        }
        return people;
    }

    /** 关系记录里「对方」的用户 ID。 */
    private static long otherId(WikiUserRelation relation, long ownerId) {
        long from = relation.getFromUserId() == null ? 0 : relation.getFromUserId();
        long to = relation.getToUserId() == null ? 0 : relation.getToUserId();
        if (from == ownerId) {
            return to;
        }
        if (to == ownerId) {
            return from;
        }
        return 0;
    }

    /** 头像文件名 → URL（空 / 未知回退默认头像）。 */
    private String avatarUrl(String fileName) {
        if (Strings.isBlank(fileName) || avatars == null) {
            return UserStore.DEFAULT_AVATAR_URL;
        }
        String url = avatars.url(fileName);
        return Strings.isBlank(url) ? UserStore.DEFAULT_AVATAR_URL : url;
    }

    /** 留言作者的用户名（一次批量查，避免每条留言查一次）。 */
    private Map<Long, String> authorNames(List<WikiUserBoard> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserBoard row : rows) {
            if (row.getFromUserId() != null) {
                ids.add(row.getFromUserId());
            }
        }
        return users == null || ids.isEmpty() ? Map.of() : users.names(ids);
    }

    /** 留言作者的头像文件名 → URL（一次批量查）。 */
    private Map<Long, String> authorAvatars(List<WikiUserBoard> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserBoard row : rows) {
            if (row.getFromUserId() != null) {
                ids.add(row.getFromUserId());
            }
        }
        if (users == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> files = users.avatars(ids);
        Map<Long, String> urls = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : files.entrySet()) {
            urls.put(entry.getKey(), avatarUrl(entry.getValue()));
        }
        return urls;
    }

    /** 与资料卡主人的好友关系（匿名 / 本人 / 账号不存在时为空串 → 视图按 NONE 处理）。 */
    private String relationState(Viewer viewer, long userId, boolean self) {
        if (viewer == null || !viewer.loggedIn() || userId <= 0 || self) {
            return "";
        }
        return friends.relationState(viewer.id(), userId);
    }

    /** 资料卡主人有没有把我列为仇敌（用户页给一句提示）。 */
    private boolean incomingFoe(Viewer viewer, long userId, boolean self) {
        return viewer != null && viewer.loggedIn() && userId > 0 && !self
                && friends.isFoeOf(userId, viewer.id());
    }
}
