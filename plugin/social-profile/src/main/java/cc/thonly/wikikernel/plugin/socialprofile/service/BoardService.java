package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户留言板规则（仿 MediaWiki SocialProfile 的 UserBoard）：
 * <ul>
 *     <li><b>留言</b>：需要登录；收件人必须存在；不能给自己留言；收件人可以关闭「允许他人留言」
 *     （社交偏好 {@code socialProfile.pref.allowBoard}）；正文走 wikitext（展示时渲染），
 *     长度上限见 {@code socialProfile.board.maxLength}（默认 2000）；</li>
 *     <li><b>回复</b>：回复必须挂在存在的、未删除的父留言上，收件人取父留言的收件人（板主）；</li>
 *     <li><b>删除</b>：板主 / 发件人本人 / 管理员可以删（管理员判断由 API 层用
 *     {@code common.service.AccessCheck} 的用户组做，插件不自己写规则）；
 *     删除是**软删除**（{@code deleted = true}，列表里保留「已删除」占位）；</li>
 *     <li><b>限流</b>：{@code social-profile-board} 分类按用户 ID 计数（留言 / 回复 / 删除都算一次），
 *     规则与其它模块同一套（{@code wikikernel.rate-limit.*}）。</li>
 * </ul>
 * 留言成功后按 {@link PointsRules.Action#BOARD_POST} 给分（默认 5 分，可配置）。
 */
@Component
public final class BoardService {

    /** 限流分类（{@code wikikernel.rate-limit.social-profile-board.*}）。 */
    public static final String RATE_LIMIT_CATEGORY = "social-profile-board";

    /** 默认正文长度上限。 */
    public static final int DEFAULT_MAX_LENGTH = 2000;

    /** 用户页资料卡下方展示的留言条数（默认 5，键 {@code socialProfile.board.pageSize}）。 */
    public static final int DEFAULT_PROFILE_LIMIT = 5;

    /** 特殊:留言板一页的留言条数（默认 20，键 {@code socialProfile.board.pageLimit}）。 */
    public static final int DEFAULT_PAGE_LIMIT = 20;

    private final UserBoardStore board;
    private final UserStore users;
    private final RateLimit rateLimit;
    private final SocialProfileSupport support;
    private final ProfileService profiles;
    private final PointsService points;

    public BoardService(UserBoardStore board, UserStore users, RateLimit rateLimit,
                        SocialProfileSupport support, ProfileService profiles, PointsService points) {
        this.board = board;
        this.users = users;
        this.rateLimit = rateLimit;
        this.support = support;
        this.profiles = profiles;
        this.points = points;
    }

    /** 正文长度上限（配置 {@code socialProfile.board.maxLength}）。 */
    public int maxLength() {
        return Math.max(support.setting("board.maxLength", "socialProfile.board.maxLength",
                DEFAULT_MAX_LENGTH), 1);
    }

    /** 资料卡下方展示的留言条数上限。 */
    public int profileLimit() {
        return Math.max(support.setting("board.pageSize", "socialProfile.board.pageSize",
                DEFAULT_PROFILE_LIMIT), 0);
    }

    /** 特殊:留言板一页的留言条数上限。 */
    public int pageLimit() {
        return Math.max(support.setting("board.pageLimit", "socialProfile.board.pageLimit",
                DEFAULT_PAGE_LIMIT), 1);
    }

    /** 某个收件人（板主）最近的留言，新的在前（含已删除的占位，调用方按 {@code deleted} 渲染）。 */
    public List<WikiUserBoard> recent(long toUserId, int limit) {
        return board.received(toUserId, limit);
    }

    /** 某个收件人的留言总数（含已删除）。 */
    public long count(long toUserId) {
        return board.count(toUserId);
    }

    /** 某条留言的回复（按时间升序）。 */
    public List<WikiUserBoard> replies(long parentId, int limit) {
        return board.replies(parentId, limit);
    }

    /** 按 ID 查留言（含已删除）。 */
    public Optional<WikiUserBoard> find(long messageId) {
        return board.find(messageId);
    }

    /** 最近的全站留言（新的在前）。 */
    public List<WikiUserBoard> recentAll(int limit) {
        return board.recent(limit);
    }

    /** 板主是否允许他人留言（收件人没设过偏好 = 允许）。 */
    public boolean allowsBoard(long toUserId) {
        return profiles == null || profiles.allowsBoard(toUserId);
    }

    /**
     * 匿名用户能不能留言（仿 SocialProfile 的 {@code $wgSocialProfileAllowAnonBoardWrites}）：
     * 键 {@code socialProfile.board.allowAnonymous}，默认 **false**（需要登录）。
     */
    public boolean allowsAnonymous() {
        return support.setting("board.allowAnonymous", "socialProfile.board.allowAnonymous", false);
    }

    /**
     * 给某人留言（顶层留言）。
     *
     * @param toUserId 收件人（板主）
     * @return 已写入的留言
     * @throws BoardException 收件人不存在 / 给自己留言 / 对方不接受留言 / 正文为空或过长 / 被限流
     */
    public WikiUserBoard post(long fromUserId, long toUserId, String content) {
        return write(fromUserId, toUserId, null, content);
    }

    /**
     * 回复某条留言（父留言的收件人就是板主；父留言必须存在且未删除）。
     *
     * @throws BoardException 父留言不存在 / 正文非法 / 被限流
     */
    public WikiUserBoard reply(long fromUserId, long parentId, String content) {
        WikiUserBoard parent = board.find(parentId)
                .orElseThrow(() -> new BoardException(BoardException.PARENT_NOT_FOUND));
        if (Boolean.TRUE.equals(parent.getDeleted())) {
            throw new BoardException(BoardException.PARENT_NOT_FOUND);
        }
        return write(fromUserId, parent.getToUserId(), parent.getId(), content);
    }

    /** 校验 + 限流 + 落库（留言与回复共用；回复的收件人取父留言的板主）。 */
    private WikiUserBoard write(long fromUserId, long toUserId, Long parentId, String content) {
        requireLogin(fromUserId);
        if (users == null || users.byId(toUserId).isEmpty()) {
            throw new BoardException(BoardException.USER_NOT_FOUND);
        }
        if (Objects.equals(fromUserId, toUserId)) {
            throw new BoardException(BoardException.SELF_NOT_ALLOWED);
        }
        if (!allowsBoard(toUserId)) {
            throw new BoardException(BoardException.BOARD_CLOSED);
        }
        String text = Strings.trimToEmpty(content);
        if (text.isEmpty()) {
            throw new BoardException(BoardException.EMPTY);
        }
        if (text.length() > maxLength()) {
            throw new BoardException(BoardException.TOO_LONG);
        }
        checkRateLimit(fromUserId);
        WikiUserBoard message = new WikiUserBoard(null, toUserId, fromUserId, text, parentId, false,
                LocalDateTime.now());
        WikiUserBoard saved = board.insert(message);
        if (points != null) {
            points.award(fromUserId, PointsRules.Action.BOARD_POST);
        }
        return saved;
    }

    /**
     * 删除留言：板主 / 发件人 / 管理员可以删；软删除。
     *
     * @param admin 当前用户是否为管理员（由 API 层用 {@code AccessCheck} 判定后传入）
     * @throws BoardException 未登录 / 留言不存在 / 无权删除 / 被限流
     */
    public boolean delete(long actorUserId, long messageId, boolean admin) {
        requireLogin(actorUserId);
        WikiUserBoard message = board.find(messageId)
                .orElseThrow(() -> new BoardException(BoardException.NOT_FOUND));
        boolean owner = Objects.equals(message.getToUserId(), actorUserId);
        boolean author = Objects.equals(message.getFromUserId(), actorUserId);
        if (!admin && !owner && !author) {
            throw new BoardException(BoardException.FORBIDDEN);
        }
        if (Boolean.TRUE.equals(message.getDeleted())) {
            return false;
        }
        checkRateLimit(actorUserId);
        message.setDeleted(true);
        board.update(message);
        return true;
    }

    private static void requireLogin(long userId) {
        if (userId <= 0) {
            throw new BoardException(BoardException.LOGIN_REQUIRED);
        }
    }

    /** 限流：进入受保护逻辑前先 check，通过后记一次事件。 */
    private void checkRateLimit(long userId) {
        if (rateLimit == null) {
            return;
        }
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, String.valueOf(userId));
        if (decision.limited()) {
            throw new BoardException(BoardException.RATE_LIMITED, decision.retryAfterSeconds());
        }
        rateLimit.hit(RATE_LIMIT_CATEGORY, String.valueOf(userId));
    }

    /**
     * 留言操作失败：{@link #getMessage()} 是**消息键**（调用方本地化），
     * {@link #retryAfterSeconds()} 只在 {@link #RATE_LIMITED} 时有意义。
     */
    public static class BoardException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 需要登录。 */
        public static final String LOGIN_REQUIRED = "permissions.loginRequired";
        /** 留言板主人不存在。 */
        public static final String USER_NOT_FOUND = "socialProfile.error.userNotFound";
        /** 不能给自己留言。 */
        public static final String SELF_NOT_ALLOWED = "socialProfile.error.boardSelf";
        /** 对方关闭了留言板。 */
        public static final String BOARD_CLOSED = "socialProfile.error.boardClosed";
        /** 正文为空。 */
        public static final String EMPTY = "socialProfile.error.boardEmpty";
        /** 正文过长。 */
        public static final String TOO_LONG = "socialProfile.error.boardTooLong";
        /** 要回复的留言不存在。 */
        public static final String PARENT_NOT_FOUND = "socialProfile.error.boardParent";
        /** 留言不存在。 */
        public static final String NOT_FOUND = "socialProfile.error.boardNotFound";
        /** 无权删除。 */
        public static final String FORBIDDEN = "socialProfile.error.boardForbidden";
        /** 触发限流。 */
        public static final String RATE_LIMITED = "socialProfile.error.rateLimited";

        private final long retryAfterSeconds;

        public BoardException(String messageKey) {
            this(messageKey, 0);
        }

        public BoardException(String messageKey, long retryAfterSeconds) {
            super(messageKey);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
