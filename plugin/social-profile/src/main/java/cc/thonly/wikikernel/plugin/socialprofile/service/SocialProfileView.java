package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.model.ProfileFields;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SocialProfile 的 HTML 片段渲染（用户页资料卡顶部 / 左右两栏 / 留言墙），
 * **像素级对齐 MediaWiki SocialProfile 原版**（{@code UserProfile.css} / {@code UserBoard.css} 的
 * 类名、结构与尺寸）：
 * <ul>
 *     <li>{@link #profileTop}：{@code #profile-top}（75×75 头像 + {@code #profile-title} 大号用户名 +
 *     {@code #points-level} 红色积分徽章 + {@code #honorific-level} 等级名 + {@code .profile-actions}
 *     操作链接），由 {@code UserProfileHook.profileTopHtml} 取代 core 内置资料卡；</li>
 *     <li>{@link #userPanel}：左栏（35%，{@code #user-page-left}：统计 / 好友 / 仇敌 / 礼物 / 勋章）+
 *     右栏（60%，{@code #user-page-right}：个人信息 / 用户墙），各区用原版的
 *     {@code .user-section-heading} + {@code .user-section-title} + {@code .profile-info-container}；</li>
 *     <li>留言条目用原版 {@code UserBoard/templates/board-message.mustache} 的结构
 *     （{@code .user-board-message} / {@code -from} / {@code -time} / {@code -content} / {@code -image}
 *     / {@code -body} / {@code -links}）。</li>
 * </ul>
 * 安全：所有字段 HTML 转义；只有「关于我」与留言正文按 wikitext 交给 core 的 {@link WikiText} 渲染器。
 */
@Component
public final class SocialProfileView {

    /** 展示模式：用户页（资料卡顶部 + 左右两栏）。 */
    public static final String SCOPE_USER_PAGE = "user";
    /** 展示模式：特殊:留言板（板面里不再显示「查看全部」）。 */
    public static final String SCOPE_BOARD = "board";

    /** 左栏头像列表里最多显示几个（原版 {@code RelationshipListLookup} 的 count=4）。 */
    private static final int SIDE_LIST_LIMIT = 4;
    /** 右栏留言墙内嵌显示多少条（原版 {@code displayMessages(..., 10)}）。 */
    private static final int BOARD_EMBED_LIMIT = 10;

    /**
     * 一次资料 / 板面渲染的数据。
     *
     * @param scope            展示模式（{@link #SCOPE_USER_PAGE} / {@link #SCOPE_BOARD}）
     * @param userId           资料卡所属用户 ID（0 = 账号不存在）
     * @param username         资料卡所属用户名
     * @param self             看页面的是不是本人
     * @param canPost          当前用户能不能在墙上留言（登录 + 不是本人 + 对方允许留言）
     * @param fields           资料字段
     * @param preferences      板主的社交偏好
     * @param friendCount      好友数
     * @param giftCounts       收到的礼物（种类 id → 件数，已按清单顺序）
     * @param giftTotal        收到的礼物总数
     * @param awardIds         获得的奖励种类 id
     * @param boardTotal       留言总数（含已删除）
     * @param boardRows        留言行（新的在前，含回复行与已删除占位；一次批量取回）
     * @param boardMoreUrl     留言板完整页面地址
     * @param boardAuthorNames 留言作者名（批量查）
     * @param relationState    当前用户与资料卡主人的关系：NONE / PENDING / INCOMING / FRIEND / FOE
     * @param incomingFoe      资料卡主人是否把我列为仇敌
     * @param pendingFriends   资料卡主人待处理的好友请求数
     * @param avatarUrl        资料卡头像地址（{@code /avatars/…} 或默认头像）
     * @param points           当前积分（{@code #points-level} 徽章）
     * @param levelName        等级名（{@code #honorific-level}，如「见习编辑者」）
     * @param friends          好友列表（左栏头像，最多 {@value #SIDE_LIST_LIMIT} 个）
     * @param foes             仇敌列表（左栏头像）
     */
    public record View(String scope,
                       long userId,
                       String username,
                       boolean self,
                       boolean canPost,
                       ProfileFields fields,
                       ProfileService.SocialPreferences preferences,
                       long friendCount,
                       Map<String, Long> giftCounts,
                       long giftTotal,
                       List<String> awardIds,
                       long boardTotal,
                       List<WikiUserBoard> boardRows,
                       String boardMoreUrl,
                       Map<Long, String> boardAuthorNames,
                       String relationState,
                       boolean incomingFoe,
                       long pendingFriends,
                       String avatarUrl,
                       long points,
                       String levelName,
                       List<PersonRef> friends,
                       List<PersonRef> foes,
                       boolean viewerLoggedIn,
                       Map<Long, String> boardAuthorAvatars) {

        /** 头像列表里的一位用户（左栏好友 / 仇敌）。 */
        public record PersonRef(long id, String name, String avatarUrl) {
        }

        /** 关系状态（没有关系时返回 {@code NONE}）。 */
        public String relation() {
            return relationState == null || relationState.isEmpty() ? "NONE" : relationState;
        }

        /** 留言作者显示名（批量查不到时退回「用户 #id」）。 */
        public String authorName(long userId) {
            String name = boardAuthorNames == null ? null : boardAuthorNames.get(userId);
            return name == null || name.isEmpty() ? "#" + userId : name;
        }

        /** 留言作者头像（批量查不到时返回空串，调用方回退默认头像）。 */
        public String authorAvatar(long userId) {
            String avatar = boardAuthorAvatars == null ? null : boardAuthorAvatars.get(userId);
            return avatar == null ? "" : avatar;
        }

        /** 兼容旧构造器（没有资料卡顶部数据）：头像用默认头像、积分 / 等级 / 列表为空。 */
        public View(String scope, long userId, String username, boolean self, boolean canPost,
                    ProfileFields fields, ProfileService.SocialPreferences preferences, long friendCount,
                    Map<String, Long> giftCounts, long giftTotal, List<String> awardIds, long boardTotal,
                    List<WikiUserBoard> boardRows, String boardMoreUrl,
                    Map<Long, String> boardAuthorNames) {
            this(scope, userId, username, self, canPost, fields, preferences, friendCount, giftCounts,
                    giftTotal, awardIds, boardTotal, boardRows, boardMoreUrl, boardAuthorNames,
                    "NONE", false, 0, UserStore.DEFAULT_AVATAR_URL, 0, "", List.of(), List.of(), false, Map.of());
        }

        /** 兼容旧构造器（有统计栏与关系、没有资料卡顶部数据）。 */
        public View(String scope, long userId, String username, boolean self, boolean canPost,
                    ProfileFields fields, ProfileService.SocialPreferences preferences, long friendCount,
                    Map<String, Long> giftCounts, long giftTotal, List<String> awardIds, long boardTotal,
                    List<WikiUserBoard> boardRows, String boardMoreUrl,
                    Map<Long, String> boardAuthorNames, String relationState, boolean incomingFoe,
                    long pendingFriends) {
            this(scope, userId, username, self, canPost, fields, preferences, friendCount, giftCounts,
                    giftTotal, awardIds, boardTotal, boardRows, boardMoreUrl, boardAuthorNames,
                    relationState, incomingFoe, pendingFriends,
                    UserStore.DEFAULT_AVATAR_URL, 0, "", List.of(), List.of(), false, Map.of());
        }
    }

    private final SocialProfileSupport support;
    private final ProfileService profiles;
    private final PointsService points;
    private final FriendsService friends;
    private final WikiText wikiText;

    /**
     * 生产构造器（Spring 注入；{@code FriendsService} 用于列表渲染）。
     */
    @Autowired
    public SocialProfileView(SocialProfileSupport support, ProfileService profiles, PointsService points,
                             FriendsService friends, WikiText wikiText) {
        this.support = support;
        this.profiles = profiles;
        this.points = points;
        this.friends = friends;
        this.wikiText = wikiText;
    }

    /** 简版构造器（没有好友服务；单元测试与老调用方用）。 */
    public SocialProfileView(SocialProfileSupport support, ProfileService profiles, PointsService points,
                            WikiText wikiText) {
        this(support, profiles, points, null, wikiText);
    }

    // ==================== 资料卡顶部（SocialProfile 原版 #profile-top） ====================

    /**
     * 资料卡整体（原版 {@code #profile-top}）：75×75 头像、大号用户名、红色积分徽章、等级名、
     * 操作链接行（{@code .profile-actions}）。由 {@code UserProfileHook.profileTopHtml} 取代
     * core 内置资料卡；账号不存在时只给贡献链接。
     */
    public String profileTop(View view) {
        if (view == null || Strings.isBlank(view.username())) {
            return "";
        }
        String username = view.username();
        String avatar = Strings.isBlank(view.avatarUrl()) ? UserStore.DEFAULT_AVATAR_URL : view.avatarUrl();
        StringBuilder html = new StringBuilder("<div id=\"profile-top\">");
        // 头像（原版 #profile-image：白底、1px #dcdcdc 边框、3px 内边距；75×75 = wAvatar 'l'）
        html.append("<div id=\"profile-image\"><img class=\"avatar\" width=\"75\" height=\"75\" src=\"")
                .append(escape(avatar)).append("\" alt=\"").append(escape(username)).append("\"/></div>");
        // 右栏：标题 + 积分徽章 + 等级名 + 操作链接
        html.append("<div id=\"profile-right\">");
        html.append("<div id=\"profile-title-container\">");
        html.append("<div id=\"profile-title\">").append(escape(username)).append("</div>");
        if (view.userId() > 0) {
            String leaderboard = SocialProfileSupport.pagePath("特殊:积分排行");
            html.append("<div id=\"points-level\"><a href=\"").append(escape(leaderboard)).append("\">")
                    .append(escape(support.message("socialProfile.classic.points", null, view.points())))
                    .append("</a></div>");
            if (Strings.isNotBlank(view.levelName())) {
                html.append("<div id=\"honorific-level\"><a href=\"").append(escape(leaderboard)).append("\">(")
                        .append(escape(view.levelName())).append(")</a></div>");
            }
            html.append("<div class=\"visualClear\"></div>");
        }
        html.append("</div>");
        html.append("<div class=\"profile-actions\">").append(actions(view)).append("</div>");
        html.append("</div>");
        html.append("<div class=\"visualClear\"></div></div>");
        return html.toString();
    }

    /**
     * 操作链接行（原版 {@code .profile-actions}，粗体文字链接）：
     * 本人 = 编辑个人资料 / 上传头像 / 监视列表；登录访客 = 加为好友 / 加为仇敌 / 发送消息 / 送一份礼物；
     * 最后都跟一个「贡献」。
     */
    private String actions(View view) {
        String username = view.username();
        String self = SocialProfileSupport.encodeQuery(username);
        boolean account = view.userId() > 0;
        StringBuilder html = new StringBuilder();
        if (account && view.self()) {
            html.append(link(SocialProfileSupport.pagePath("特殊:编辑资料"),
                    support.message("socialProfile.classic.editProfile"), null));
            html.append(link(SocialProfileSupport.pagePath("特殊:上传头像"),
                    support.message("socialProfile.classic.uploadAvatar"), null));
            html.append(link(SocialProfileSupport.pagePath("特殊:编辑监视列表"),
                    support.message("socialProfile.classic.watchlist"), null));
        } else if (account && view.viewerLoggedIn()) {
            switch (view.relation()) {
                case "FRIEND" -> html.append(action("a", ".sp-friend-remove",
                        "data-user=\"" + escape(username) + "\"",
                        support.message("socialProfile.classic.removeFriend")));
                case "FOE" -> html.append(action("a", ".sp-foe-remove",
                        "data-user=\"" + escape(username) + "\"",
                        support.message("socialProfile.classic.removeFoe")));
                case "PENDING", "INCOMING" -> html.append(link(
                        SocialProfileSupport.pagePath("特殊:好友"),
                        support.message("socialProfile.classic.friendPending"), null));
                default -> {
                    html.append(action("a", null, "data-friend-add=\"" + escape(username) + "\"",
                            support.message("socialProfile.classic.addFriend")));
                    html.append(action("a", ".sp-foe-add", "data-user=\"" + escape(username) + "\"",
                            support.message("socialProfile.classic.addFoe")));
                }
            }
            html.append(link("/wiki/特殊:私信?write=1&to=" + self,
                    support.message("socialProfile.classic.sendMessage"), null));
            html.append(link(SocialProfileSupport.pagePath("特殊:送礼物?user=" + self),
                    support.message("socialProfile.classic.sendGift"), null));
        }
        html.append(link(SocialProfileSupport.pagePath("特殊:用户贡献?name=" + self),
                support.message("socialProfile.classic.contributions"), null));
        return html.toString();
    }

    // ==================== 用户页两栏 ====================

    /**
     * 用户页资料卡下方的整宽面板：原版 SocialProfile 的左右两栏
     * （{@code #user-page-left} 35% / {@code #user-page-right} 60%，结尾 {@code .visualClear}）。
     */
    public String userPanel(View view) {
        if (view == null || Strings.isBlank(view.username())) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"user-page-left\" class=\"clearfix\">");
        html.append(statisticsSection(view));
        html.append(peopleSection(view, "socialProfile.classic.friends", view.friends()));
        html.append(peopleSection(view, "socialProfile.classic.foes", view.foes()));
        html.append(iconsSection(view, "socialProfile.classic.gifts", view.giftCounts(), "特殊:礼物"));
        html.append(iconsSection(view, "socialProfile.classic.awards",
                awardIcons(view), "特殊:奖励"));
        html.append("</div>");
        html.append("<div id=\"user-page-right\" class=\"clearfix\">");
        html.append(personalSection(view));
        html.append(boardSection(view));
        html.append("</div><div class=\"visualClear\"></div>");
        return html.toString();
    }

    /** 一个原版样式的区（{@code .user-section-heading} + 可选右侧动作 + 可选左侧计数 + 内容）。 */
    private String section(String title, String actionRight, String actionLeft, String body) {
        StringBuilder html = new StringBuilder("<div class=\"user-section-heading\"><div class=\"user-section-title\">")
                .append(escape(title))
                .append("</div><div class=\"user-section-actions\"><div class=\"action-right\">")
                .append(actionRight == null ? "" : actionRight)
                .append("</div><div class=\"action-left\">")
                .append(actionLeft == null ? "" : actionLeft)
                .append("</div><div class=\"visualClear\"></div></div></div><div class=\"visualClear\"></div>");
        html.append(body == null ? "" : body);
        return html.toString();
    }

    // ==================== 统计 ====================

    /** 左栏「统计」：积分 / 等级 / 好友 / 礼物 / 勋章（为 0 的项不显示，与原版一致）。 */
    private String statisticsSection(View view) {
        if (view.userId() <= 0) {
            return "";
        }
        List<String> rows = new ArrayList<>();
        if (view.points() > 0) {
            rows.add(row(support.message("socialProfile.classic.statPoints"),
                    String.valueOf(view.points())));
        }
        if (view.friendCount() > 0) {
            rows.add(row(support.message("socialProfile.classic.statFriends"),
                    String.valueOf(view.friendCount())));
        }
        if (view.giftTotal() > 0) {
            rows.add(row(support.message("socialProfile.classic.statGifts"),
                    String.valueOf(view.giftTotal())));
        }
        int awards = view.awardIds() == null ? 0 : view.awardIds().size();
        if (awards > 0) {
            rows.add(row(support.message("socialProfile.classic.statAwards"), String.valueOf(awards)));
        }
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder("<div class=\"profile-info-container bold-fix\">");
        for (String line : rows) {
            body.append(line);
        }
        body.append("</div>");
        return section(support.message("socialProfile.classic.statistics"), null, null, body.toString());
    }

    /** 统计 / 个人信息的一行：{@code <div><b>标签</b> 值</div>}（原版 getProfileSection / getStatsRow 的 <b> 后换行渲染成空格）。 */
    private static String row(String label, String value) {
        return "<div><b>" + escape(label) + "</b> " + value + "</div>";
    }

    // ==================== 好友 / 仇敌 / 礼物 / 勋章（左栏头像与图标列表） ====================

    /** 好友 / 仇敌：头像列表（原版 {@code .user-relationship-container}，最多 4 个 + 「查看全部」）。 */
    private String peopleSection(View view, String titleKey, List<View.PersonRef> people) {
        if (view.userId() <= 0 || people == null || people.isEmpty()) {
            return "";
        }
        String more = people.size() > SIDE_LIST_LIMIT
                ? link(SocialProfileSupport.pagePath("特殊:好友?user="
                        + SocialProfileSupport.encodeQuery(view.username())),
                        support.message("socialProfile.classic.viewAll"), null)
                : null;
        StringBuilder body = new StringBuilder("<div class=\"user-relationship-container\">");
        int shown = 0;
        for (View.PersonRef person : people) {
            if (shown++ >= SIDE_LIST_LIMIT) {
                break;
            }
            String name = Strings.isBlank(person.name()) ? "#" + person.id() : person.name();
            String avatar = Strings.isBlank(person.avatarUrl()) ? UserStore.DEFAULT_AVATAR_URL : person.avatarUrl();
            body.append("<a href=\"").append(escape(SocialProfileSupport.pagePath("用户:" + name)))
                    .append("\" title=\"").append(escape(name)).append("\"><img width=\"50\" height=\"50\" src=\"")
                    .append(escape(avatar)).append("\" alt=\"").append(escape(name)).append("\"/></a>");
        }
        body.append("<div class=\"visualClear\"></div></div>");
        return section(support.message(titleKey), more, null, body.toString());
    }

    /** 礼物 / 勋章：图标列表（原版 {@code .user-gift-container}，最多 4 个 + 「查看全部」）。 */
    private String iconsSection(View view, String titleKey, Map<String, Long> icons, String specialPage) {
        if (view.userId() <= 0 || icons == null || icons.isEmpty()) {
            return "";
        }
        String more = icons.size() > SIDE_LIST_LIMIT
                ? link(SocialProfileSupport.pagePath(specialPage + "?user="
                        + SocialProfileSupport.encodeQuery(view.username())),
                        support.message("socialProfile.classic.viewAll"), null)
                : null;
        StringBuilder body = new StringBuilder("<div class=\"user-gift-container\">");
        int shown = 0;
        for (Map.Entry<String, Long> entry : icons.entrySet()) {
            if (shown++ >= SIDE_LIST_LIMIT) {
                break;
            }
            String name = giftName(entry.getKey());
            body.append("<a href=\"").append(escape(SocialProfileSupport.pagePath(specialPage + "?user="
                            + SocialProfileSupport.encodeQuery(view.username()))))
                    .append("\" title=\"").append(escape(name)).append("\"><span class=\"sp-gift-icon\">")
                    .append(giftIcon(entry.getKey())).append("</span></a>");
        }
        body.append("<div class=\"visualClear\"></div></div>");
        return section(support.message(titleKey), more, null, body.toString());
    }

    /** 勋章（奖励）id → 件数的图标表（原版 SystemGifts 的列表）。 */
    private Map<String, Long> awardIcons(View view) {
        if (view.awardIds() == null || view.awardIds().isEmpty()) {
            return Map.of();
        }
        Map<String, Long> icons = new LinkedHashMap<>();
        for (String id : view.awardIds()) {
            icons.merge(id, 1L, Long::sum);
        }
        return icons;
    }

    // ==================== 个人信息（右栏） ====================

    /**
     * 右栏「个人信息」（原版 {@code getPersonalInfo}）：每行 {@code <b>标签</b>值}；
     * 空字段本人看到「编辑自己的个人资料」链接、别人看到「没有提供」。
     */
    private String personalSection(View view) {
        if (view.userId() <= 0) {
            return "";
        }
        ProfileFields fields = view.fields() == null ? ProfileFields.empty() : view.fields();
        ProfileService.SocialPreferences prefs = view.preferences() == null
                ? ProfileService.SocialPreferences.DEFAULT : view.preferences();
        List<String> rows = new ArrayList<>();
        addPersonalRow(rows, view, support.message("socialProfile.field.gender"),
                escape(profiles.genderLabel(fields.gender())), fields.gender());
        if (prefs.showBirthday()) {
            addPersonalRow(rows, view, support.message("socialProfile.field.birthday"),
                    escape(fields.birthday()), fields.birthday());
        }
        if (prefs.showLocation()) {
            addPersonalRow(rows, view, support.message("socialProfile.field.location"),
                    escape(fields.location()), fields.location());
        }
        addPersonalRow(rows, view, support.message("socialProfile.field.hometown"),
                escape(fields.hometown()), fields.hometown());
        addPersonalRow(rows, view, support.message("socialProfile.field.occupation"),
                escape(fields.occupation()), fields.occupation());
        addPersonalRow(rows, view, support.message("socialProfile.field.interests"),
                escape(fields.interests()), fields.interests());
        if (Strings.isNotBlank(fields.website())) {
            String url = escape(fields.website());
            addPersonalRow(rows, view, support.message("socialProfile.field.website"),
                    "<a rel=\"nofollow noopener\" target=\"_blank\" href=\"" + url + "\">" + url + "</a>",
                    fields.website());
        }
        if (Strings.isNotBlank(fields.about())) {
            rows.add(row(support.message("socialProfile.field.about"),
                    renderWiki(fields.about(), "用户:" + view.username())));
        }
        StringBuilder body = new StringBuilder("<div class=\"profile-info-container\">");
        for (String line : rows) {
            body.append(line);
        }
        body.append("</div>");
        return section(support.message("socialProfile.classic.personalInfo"), null, null, body.toString());
    }

    /** 个人信息一行：有值输出值；空值本人给编辑链接、别人给「没有提供」。 */
    private void addPersonalRow(List<String> rows, View view, String label, String valueHtml, String rawValue) {
        if (Strings.isNotBlank(rawValue)) {
            rows.add(row(label, valueHtml));
            return;
        }
        if (view.self()) {
            rows.add(row(label, "<a href=\"" + escape(SocialProfileSupport.pagePath("特殊:编辑资料"))
                    + "\">" + escape(support.message("socialProfile.classic.editOwn")) + "</a>"));
        } else {
            rows.add(row(label, escape(support.message("socialProfile.classic.notProvided"))));
        }
    }

    // ==================== 用户墙（右栏，原版 UserBoard） ====================

    /**
     * 右栏「用户墙」（原版 {@code getUserBoard}）：区标题 + 计数 / 查看全部，
     * 「墙到墙」留言表单（登录访客），以及原版结构（{@code .user-board-message*}）的留言列表。
     */
    private String boardSection(View view) {
        String actionRight = null;
        if (!SCOPE_BOARD.equals(view.scope()) && view.boardMoreUrl() != null) {
            actionRight = link(view.boardMoreUrl(), support.message("socialProfile.classic.viewAll"), null);
        }
        String actionLeft = view.boardTotal() > 0
                ? escape(support.message("socialProfile.classic.countSeparator", null,
                Math.min(view.boardTotal(), BOARD_EMBED_LIMIT), view.boardTotal()))
                : null;
        StringBuilder body = new StringBuilder();
        body.append(boardForm(view));
        body.append("<div id=\"user-page-board\">");
        List<WikiUserBoard> rows = view.boardRows() == null ? List.of() : view.boardRows();
        if (rows.isEmpty()) {
            body.append("<div class=\"no-info-container\">")
                    .append(escape(support.message("socialProfile.classic.noMessages")))
                    .append("</div>");
        } else {
            int shown = 0;
            for (WikiUserBoard row : rows) {
                if (shown++ >= BOARD_EMBED_LIMIT) {
                    break;
                }
                body.append(boardMessage(row, view));
            }
        }
        body.append("</div>");
        return section(support.message("socialProfile.classic.board"), actionRight, actionLeft, body.toString());
    }

    /** 留言表单（原版 {@code .user-page-message-form}；登录访客可写、被封禁给红字、匿名提示登录）。 */
    private String boardForm(View view) {
        if (view.userId() <= 0) {
            return "";
        }
        if (view.canPost()) {
            return "<div class=\"user-page-message-form\">"
                    + "<form class=\"sp-board-form\" method=\"post\" action=\"/api/social-profile/board/post\""
                    + " data-to=\"" + escape(view.username()) + "\">"
                    + "<input type=\"hidden\" name=\"to\" value=\"" + escape(view.username()) + "\"/>"
                    + "<input type=\"hidden\" name=\"parent\" value=\"\"/>"
                    + "<p><textarea name=\"content\" id=\"message\" cols=\"43\" rows=\"4\"></textarea></p>"
                    + "<div class=\"user-page-message-box-button\">"
                    + "<input type=\"submit\" class=\"site-button\" value=\""
                    + escape(support.message("socialProfile.classic.sendButton")) + "\"/></div>"
                    + "<div class=\"form-message\" role=\"alert\" hidden></div>"
                    + "</form></div>";
        }
        if (view.self()) {
            return "";
        }
        String key = !allowsBoard(view) ? "socialProfile.classic.boardClosed"
                : "socialProfile.classic.loginHint";
        return "<div class=\"user-page-message-form\">"
                + escape(support.message(key))
                + "</div>";
    }

    private boolean allowsBoard(View view) {
        return view.preferences() == null || view.preferences().allowBoard();
    }

    /**
     * 一条留言（原版 {@code board-message.mustache}）：作者行 + 时间 + 头像 + 正文 + 操作链接；
     * 已删除显示占位；作者 / 板主可删（红字链接）。
     */
    private String boardMessage(WikiUserBoard row, View view) {
        long authorId = row.getFromUserId() == null ? 0 : row.getFromUserId();
        String author = view.authorName(authorId);
        String authorUrl = escape(SocialProfileSupport.pagePath("用户:" + author));
        String avatar = view.authorAvatar(authorId);
        StringBuilder html = new StringBuilder("<div class=\"user-board-message\">");
        html.append("<div class=\"user-board-message-from\"><a href=\"").append(authorUrl)
                .append("\" title=\"").append(escape(author)).append("\">").append(escape(author))
                .append(" </a></div>");
        html.append("<div class=\"user-board-message-time\">")
                .append(escape(support.message("socialProfile.classic.postedAgo", null,
                        relativeTime(row.getCreateAt()))))
                .append("</div>");
        html.append("<div class=\"user-board-message-content\">");
        html.append("<div class=\"user-board-message-image\"><a href=\"").append(authorUrl)
                .append("\" title=\"").append(escape(author)).append("\"><img width=\"30\" height=\"30\" src=\"")
                .append(escape(Strings.isBlank(avatar) ? UserStore.DEFAULT_AVATAR_URL : avatar))
                .append("\" alt=\"").append(escape(author)).append("\"/></a></div>");
        html.append("<div class=\"user-board-message-body\">");
        if (Boolean.TRUE.equals(row.getDeleted())) {
            html.append("<p>").append(escape(support.message("socialProfile.board.deleted"))).append("</p>");
        } else {
            html.append(renderWiki(row.getContent(), "用户:" + view.username()));
        }
        html.append("</div><div class=\"visualClear\"></div></div>");
        html.append("<div class=\"user-board-message-links\">");
        if (view.canPost()) {
            html.append("<a href=\"#\" class=\"sp-reply-button\" data-parent=\"")
                    .append(row.getId()).append("\">")
                    .append(escape(support.message("socialProfile.classic.reply"))).append("</a>");
        }
        if (view.self() || authorId == view.userId()) {
            html.append("<span class=\"user-board-red\"><a href=\"#\" class=\"sp-delete-button\" data-id=\"")
                    .append(row.getId()).append("\">")
                    .append(escape(support.message("socialProfile.classic.delete"))).append("</a></span>");
        }
        html.append("</div></div>");
        return html.toString();
    }

    /** 相对时间（原版 {@code getTimeAgo} 的常用粒度：分钟 / 小时 / 天）。 */
    private String relativeTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        Duration duration = Duration.between(time, LocalDateTime.now());
        long minutes = Math.max(duration.toMinutes(), 1);
        if (minutes < 60) {
            return support.message("socialProfile.classic.minutesAgo", null, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return support.message("socialProfile.classic.hoursAgo", null, hours);
        }
        return support.message("socialProfile.classic.daysAgo", null, hours / 24);
    }

    // ==================== 工具 ====================

    /** 一个操作链接（原版 {@code .profile-actions a} 是普通粗体链接，不用按钮）。 */
    private String link(String url, String label, String extraAttrs) {
        return "<a href=\"" + escape(url == null ? "#" : url) + "\""
                + (extraAttrs == null ? "" : " " + extraAttrs) + ">"
                + escape(label) + "</a>";
    }

    /** 带 class / 属性的操作元素（好友 / 仇敌这类由脚本 POST 的动作）。 */
    private String action(String tag, String className, String attrs, String label) {
        return "<" + tag + (className == null ? "" : " class=\"" + className + "\"")
                + " href=\"#\" " + attrs + ">" + escape(label) + "</" + tag + ">";
    }

    /** wikitext → HTML（没有渲染桥时退回转义后的纯文本）。 */
    private String renderWiki(String wikitext, String pageTitle) {
        String text = Strings.trimToEmpty(wikitext);
        if (text.isEmpty()) {
            return "";
        }
        if (wikiText == null) {
            return "<p>" + escape(text) + "</p>";
        }
        String html = wikiText.toHtml(text, pageTitle);
        return html == null ? "" : html;
    }

    /** 礼物 / 奖励 id → 显示名。 */
    private String giftName(String id) {
        if (id == null) {
            return "";
        }
        String name = support.message("socialProfile.gift." + id, null);
        return Strings.isBlank(name) ? id : name;
    }

    /** 礼物图标（纯装饰的字符，避免额外图片资源）。 */
    private static String giftIcon(String giftId) {
        return switch (giftId == null ? "" : giftId) {
            case "star" -> "★";
            case "heart" -> "♥";
            case "cookie" -> "●";
            case "rose" -> "✿";
            case "cake" -> "▲";
            case "beer" -> "◆";
            case "trophy" -> "♛";
            case "kitten" -> "◕";
            default -> "◆";
        };
    }

    private static String escape(String value) {
        return SocialProfileSupport.escape(value);
    }
}
