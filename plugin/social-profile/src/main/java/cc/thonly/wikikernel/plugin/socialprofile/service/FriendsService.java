package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 好友 / 仇敌规则（仿 MediaWiki SocialProfile 的 {@code Special:Friends} / Friend &amp; Foe）：
 * <ul>
 *     <li><b>加好友</b>：登录 + 对方存在 + 不能加自己；发起时写一行 PENDING（重复发起返回同一个请求，
 *     幂等）；对方已经加过我（我这边有 PENDING）时直接**双向接受**（避免两边互等）；</li>
 *     <li><b>接受 / 拒绝</b>：只有收件人（{@code toUserId}）能操作；接受时把请求改成 ACCEPTED
 *     并补一行反向 ACCEPTED（好友是双向的，好友数按 from 方向数即可）；拒绝 = 删除请求行；</li>
 *     <li><b>取消 / 解除</b>：好友关系双方都能解除（删掉两个方向的行）；自己发出的 PENDING 可以撤回；</li>
 *     <li><b>仇敌（foe）</b>：单向、立即生效、不需要对方同意，只在**自己的**仇敌列表里出现；</li>
 *     <li><b>限流</b>：{@code social-profile-friend} 分类按用户 ID 计数（每次变更算一次）。</li>
 * </ul>
 * 加好友成功时双方各得一次 {@link PointsRules.Action#FRIEND} 积分（默认 10 分）。
 */
@Component
public final class FriendsService {

    /** 限流分类（{@code wikikernel.rate-limit.social-profile-friend.*}）。 */
    public static final String RATE_LIMIT_CATEGORY = "social-profile-friend";

    /** 好友 / 仇敌列表的条数上限（默认 200，键 {@code socialProfile.friends.limit}）。 */
    public static final int DEFAULT_LIST_LIMIT = 200;

    private final UserRelationStore relations;
    private final UserStore users;
    private final RateLimit rateLimit;
    private final SocialProfileSupport support;
    private final PointsService points;

    public FriendsService(UserRelationStore relations, UserStore users, RateLimit rateLimit,
                          SocialProfileSupport support, PointsService points) {
        this.relations = relations;
        this.users = users;
        this.rateLimit = rateLimit;
        this.support = support;
        this.points = points;
    }

    /** 列表条数上限。 */
    public int listLimit() {
        return Math.max(support.setting("friends.limit", "socialProfile.friends.limit",
                DEFAULT_LIST_LIMIT), 1);
    }

    /** 某人已接受的好友关系（自己出发的方向），新的在前。 */
    public List<WikiUserRelation> friends(long userId) {
        return relations.outgoing(userId, WikiUserRelation.TYPE_FRIEND, listLimit()).stream()
                .filter(row -> WikiUserRelation.STATUS_ACCEPTED.equals(row.getStatus()))
                .toList();
    }

    /** 某人的仇敌关系（自己出发的方向），新的在前。 */
    public List<WikiUserRelation> foes(long userId) {
        return relations.outgoing(userId, WikiUserRelation.TYPE_FOE, listLimit());
    }

    /** 发给某人的待处理好友请求（收件箱式列表），新的在前。 */
    public List<WikiUserRelation> incomingRequests(long userId) {
        return relations.incoming(userId, WikiUserRelation.TYPE_FRIEND,
                        Math.max(listLimit(), 1)).stream()
                .filter(row -> WikiUserRelation.STATUS_PENDING.equals(row.getStatus()))
                .toList();
    }

    /** 某人自己发出的、还没被接受的请求。 */
    public List<WikiUserRelation> outgoingRequests(long userId) {
        return relations.outgoing(userId, WikiUserRelation.TYPE_FRIEND, listLimit()).stream()
                .filter(row -> WikiUserRelation.STATUS_PENDING.equals(row.getStatus()))
                .toList();
    }

    /** 别人把某人列为仇敌的记录（用户页提示「N 人把你列为仇敌」用）。 */
    public List<WikiUserRelation> incomingFoes(long userId) {
        return relations.incoming(userId, WikiUserRelation.TYPE_FOE, listLimit());
    }

    /** 待处理好友请求数量（顶栏徽标用）。 */
    public long pendingCount(long userId) {
        return userId <= 0 ? 0 : relations.pendingFriendCount(userId);
    }

    /** 好友数量（自己出发的 ACCEPTED 计数）。 */
    public long friendCount(long userId) {
        return userId <= 0 ? 0 : relations.friendCounts(List.of(userId)).getOrDefault(userId, 0L);
    }

    /** 一批用户的好友数（无数据时为空表）。 */
    public Map<Long, Long> friendCounts(Collection<Long> userIds) {
        return relations.friendCounts(userIds);
    }

    /** 与某人的好友关系状态：NONE（无关系）/ PENDING（我发出的请求）/ INCOMING（对方请求我）/ FRIEND（好友）/ FOE（我把他当仇敌 / 他把我当仇敌见 {@link #isFoeOf}）。 */
    public String relationState(long viewerId, long otherId) {
        if (viewerId <= 0 || otherId <= 0 || Objects.equals(viewerId, otherId)) {
            return "SELF";
        }
        WikiUserRelation mine = relations.find(viewerId, otherId, WikiUserRelation.TYPE_FRIEND).orElse(null);
        if (mine != null && WikiUserRelation.STATUS_ACCEPTED.equals(mine.getStatus())) {
            return "FRIEND";
        }
        if (mine != null) {
            return "PENDING";
        }
        WikiUserRelation theirs = relations.find(otherId, viewerId, WikiUserRelation.TYPE_FRIEND).orElse(null);
        if (theirs != null) {
            return WikiUserRelation.STATUS_ACCEPTED.equals(theirs.getStatus()) ? "FRIEND" : "INCOMING";
        }
        if (relations.find(viewerId, otherId, WikiUserRelation.TYPE_FOE).isPresent()) {
            return "FOE";
        }
        return "NONE";
    }

    /** 某人是否把另一个人列为仇敌。 */
    public boolean isFoeOf(long userId, long otherId) {
        return userId > 0 && otherId > 0
                && relations.find(userId, otherId, WikiUserRelation.TYPE_FOE).isPresent();
    }

    /**
     * 发送好友请求（幂等：已经发过就返回原来那一行）。
     *
     * @throws FriendException 未登录 / 用户不存在 / 加自己 / 已经是好友 / 被限流
     */
    public WikiUserRelation requestFriend(long fromUserId, long toUserId) {
        requireLogin(fromUserId);
        requireTarget(fromUserId, toUserId);
        WikiUserRelation mine = relations.find(fromUserId, toUserId, WikiUserRelation.TYPE_FRIEND)
                .orElse(null);
        if (mine != null && WikiUserRelation.STATUS_ACCEPTED.equals(mine.getStatus())) {
            throw new FriendException(FriendException.ALREADY_FRIEND);
        }
        WikiUserRelation theirs = relations.find(toUserId, fromUserId, WikiUserRelation.TYPE_FRIEND)
                .orElse(null);
        if (theirs != null && WikiUserRelation.STATUS_ACCEPTED.equals(theirs.getStatus())) {
            throw new FriendException(FriendException.ALREADY_FRIEND);
        }
        if (mine != null) {
            // 已经发过请求：幂等返回
            return mine;
        }
        checkRateLimit(fromUserId);
        if (theirs != null) {
            // 对方先发过请求 → 直接互相接受（对方是发起人、我是接受人，两边都不用再等）
            return acceptBoth(toUserId, fromUserId, theirs.getId());
        }
        WikiUserRelation created = new WikiUserRelation(null, fromUserId, toUserId,
                WikiUserRelation.TYPE_FRIEND, WikiUserRelation.STATUS_PENDING, LocalDateTime.now());
        return relations.insert(created);
    }

    /**
     * 接受好友请求（只有收件人能接受）。
     *
     * @throws FriendException 未登录 / 请求不存在 / 不是发给自己的 / 被限流
     */
    public WikiUserRelation accept(long actorUserId, long relationId) {
        requireLogin(actorUserId);
        WikiUserRelation request = relations.find(relationId)
                .orElseThrow(() -> new FriendException(FriendException.REQUEST_NOT_FOUND));
        if (!WikiUserRelation.TYPE_FRIEND.equals(request.getRelationType())
                || !Objects.equals(request.getToUserId(), actorUserId)) {
            throw new FriendException(FriendException.REQUEST_NOT_FOUND);
        }
        if (WikiUserRelation.STATUS_ACCEPTED.equals(request.getStatus())) {
            return request;
        }
        checkRateLimit(actorUserId);
        return acceptBoth(request.getFromUserId(), request.getToUserId(), relationId);
    }

    /**
     * 拒绝好友请求（只有收件人能拒绝；删除请求行）。
     *
     * @throws FriendException 未登录 / 请求不存在 / 不是发给自己的 / 被限流
     */
    public boolean reject(long actorUserId, long relationId) {
        requireLogin(actorUserId);
        WikiUserRelation request = relations.find(relationId)
                .orElseThrow(() -> new FriendException(FriendException.REQUEST_NOT_FOUND));
        if (!WikiUserRelation.TYPE_FRIEND.equals(request.getRelationType())
                || !Objects.equals(request.getToUserId(), actorUserId)) {
            throw new FriendException(FriendException.REQUEST_NOT_FOUND);
        }
        checkRateLimit(actorUserId);
        relations.delete(relationId);
        return true;
    }

    /**
     * 取消自己发出的好友请求，或解除已经是好友的关系（按用户名指定对方）。
     *
     * @throws FriendException 未登录 / 用户不存在 / 没有这层关系 / 被限流
     */
    public boolean removeFriend(long actorUserId, long otherUserId) {
        requireLogin(actorUserId);
        if (otherUserId <= 0) {
            throw new FriendException(FriendException.USER_NOT_FOUND);
        }
        WikiUserRelation mine = relations.find(actorUserId, otherUserId, WikiUserRelation.TYPE_FRIEND)
                .orElse(null);
        WikiUserRelation theirs = relations.find(otherUserId, actorUserId, WikiUserRelation.TYPE_FRIEND)
                .orElse(null);
        if (mine == null && theirs == null) {
            throw new FriendException(FriendException.NOT_FRIEND);
        }
        checkRateLimit(actorUserId);
        if (mine != null) {
            relations.delete(mine.getId());
        }
        if (theirs != null) {
            relations.delete(theirs.getId());
        }
        return true;
    }

    /**
     * 把某人加为仇敌（单向，立即生效；幂等）。
     *
     * @throws FriendException 未登录 / 用户不存在 / 加自己 / 被限流
     */
    public WikiUserRelation addFoe(long fromUserId, long toUserId) {
        requireLogin(fromUserId);
        requireTarget(fromUserId, toUserId);
        WikiUserRelation existing = relations.find(fromUserId, toUserId, WikiUserRelation.TYPE_FOE)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        checkRateLimit(fromUserId);
        return relations.insert(new WikiUserRelation(null, fromUserId, toUserId,
                WikiUserRelation.TYPE_FOE, WikiUserRelation.STATUS_ACCEPTED, LocalDateTime.now()));
    }

    /**
     * 移出仇敌列表。
     *
     * @throws FriendException 未登录 / 没有这层关系 / 被限流
     */
    public boolean removeFoe(long actorUserId, long otherUserId) {
        requireLogin(actorUserId);
        WikiUserRelation existing = relations.find(actorUserId, otherUserId, WikiUserRelation.TYPE_FOE)
                .orElseThrow(() -> new FriendException(FriendException.NOT_FOE));
        checkRateLimit(actorUserId);
        relations.delete(existing.getId());
        return true;
    }

    /** 接受好友请求：请求行 → ACCEPTED，再补反向 ACCEPTED（幂等），双方各得一次加好友积分。 */
    private WikiUserRelation acceptBoth(long requesterId, long accepterId, long relationId) {
        WikiUserRelation request = relations.find(relationId).orElse(null);
        if (request == null) {
            throw new FriendException(FriendException.REQUEST_NOT_FOUND);
        }
        request.setStatus(WikiUserRelation.STATUS_ACCEPTED);
        relations.update(request);
        // 反向行：按关系 id 排除刚改过的那一行（否则实现可能把请求行自己找回来，永远不补反向记录）
        WikiUserRelation reverse = relations.find(accepterId, requesterId, WikiUserRelation.TYPE_FRIEND)
                .filter(row -> row.getId() == null || !row.getId().equals(request.getId()))
                .orElse(null);
        if (reverse == null) {
            relations.insert(new WikiUserRelation(null, accepterId, requesterId,
                    WikiUserRelation.TYPE_FRIEND, WikiUserRelation.STATUS_ACCEPTED, LocalDateTime.now()));
        } else if (!WikiUserRelation.STATUS_ACCEPTED.equals(reverse.getStatus())) {
            reverse.setStatus(WikiUserRelation.STATUS_ACCEPTED);
            relations.update(reverse);
        }
        if (points != null) {
            points.award(requesterId, PointsRules.Action.FRIEND);
            points.award(accepterId, PointsRules.Action.FRIEND);
        }
        return request;
    }

    private void requireLogin(long userId) {
        if (userId <= 0) {
            throw new FriendException(FriendException.LOGIN_REQUIRED);
        }
    }

    private void requireTarget(long fromUserId, long toUserId) {
        if (toUserId <= 0 || users == null || users.byId(toUserId).isEmpty()) {
            throw new FriendException(FriendException.USER_NOT_FOUND);
        }
        if (Objects.equals(fromUserId, toUserId)) {
            throw new FriendException(FriendException.SELF_NOT_ALLOWED);
        }
    }

    /** 限流：进入受保护逻辑前先 check，通过后记一次事件。 */
    private void checkRateLimit(long userId) {
        if (rateLimit == null) {
            return;
        }
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, String.valueOf(userId));
        if (decision.limited()) {
            throw new FriendException(FriendException.RATE_LIMITED, decision.retryAfterSeconds());
        }
        rateLimit.hit(RATE_LIMIT_CATEGORY, String.valueOf(userId));
    }

    /** 把关系列表转成「用户名 + 用户页地址」的展示行（找不到账号的行会被跳过）。 */
    public List<Map<String, Object>> toRows(List<WikiUserRelation> rows, long viewerId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserRelation row : rows) {
            ids.add(mine(row, viewerId) ? row.getToUserId() : row.getFromUserId());
        }
        Map<Long, String> names = users == null ? Map.of() : users.names(ids);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WikiUserRelation row : rows) {
            Long otherId = mine(row, viewerId) ? row.getToUserId() : row.getFromUserId();
            String name = names.get(otherId);
            if (name == null || name.isEmpty()) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("relationId", row.getId());
            view.put("userId", otherId);
            view.put("username", name);
            view.put("userUrl", SocialProfileSupport.pagePath("用户:" + name));
            view.put("status", row.getStatus());
            view.put("pending", WikiUserRelation.STATUS_PENDING.equals(row.getStatus()));
            view.put("incoming", isTo(row, viewerId));
            view.put("time", row.getCreateAt());
            result.add(view);
        }
        return result;
    }

    /** 这一行是不是「viewer 出发」的方向（{@code fromUserId == viewerId}）。 */
    private static boolean mine(WikiUserRelation row, long viewerId) {
        return row.getFromUserId() != null && row.getFromUserId() == viewerId;
    }

    /** 这一行是不是「发给 viewer」的方向（{@code toUserId == viewerId}）。 */
    private static boolean isTo(WikiUserRelation row, long viewerId) {
        return row.getToUserId() != null && row.getToUserId() == viewerId;
    }

    /** 按用户名查用户（API 用用户名定位对方，与私信 / 留言板一致）。 */
    public Optional<UserStore.UserRef> byName(String username) {
        return users == null ? Optional.empty() : users.byName(username);
    }

    /**
     * 好友 / 仇敌操作失败：{@link #getMessage()} 是**消息键**（调用方本地化），
     * {@link #retryAfterSeconds()} 只在 {@link #RATE_LIMITED} 时有意义。
     */
    public static class FriendException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 需要登录。 */
        public static final String LOGIN_REQUIRED = "permissions.loginRequired";
        /** 对方账号不存在。 */
        public static final String USER_NOT_FOUND = "socialProfile.error.userNotFound";
        /** 不能加自己。 */
        public static final String SELF_NOT_ALLOWED = "socialProfile.error.friendSelf";
        /** 已经是好友。 */
        public static final String ALREADY_FRIEND = "socialProfile.error.friendAlready";
        /** 还不是好友 / 没有这条关系。 */
        public static final String NOT_FRIEND = "socialProfile.error.friendNotFriend";
        /** 好友请求不存在 / 不是发给你的。 */
        public static final String REQUEST_NOT_FOUND = "socialProfile.error.friendRequest";
        /** 对方不在你的仇敌列表里。 */
        public static final String NOT_FOE = "socialProfile.error.foeNotFoe";
        /** 触发限流。 */
        public static final String RATE_LIMITED = "socialProfile.error.rateLimited";

        private final long retryAfterSeconds;

        public FriendException(String messageKey) {
            this(messageKey, 0);
        }

        public FriendException(String messageKey, long retryAfterSeconds) {
            super(messageKey);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
