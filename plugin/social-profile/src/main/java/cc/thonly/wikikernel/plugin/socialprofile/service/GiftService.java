package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.model.SocialProfileKeys;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 礼物规则（仿 MediaWiki SocialProfile 的 {@code Special:SendGift} / {@code Special:ViewGifts}）：
 * <ul>
 *     <li><b>礼物清单</b>：内置若干种（见 {@link #CATALOG}），名称走消息键
 *     {@code socialProfile.gift.<id>}（**不写死中文**，缺键时显示键本身）；</li>
 *     <li><b>送礼物</b>：需要登录、收件人存在、不能送给自己；附言是**纯文本**（长度上限
 *     {@link #MESSAGE_LIMIT}，展示时转义，不按 wikitext 渲染）；送出后送礼人得
 *     {@link PointsRules.Action#GIFT} 积分（默认 3 分）；</li>
 *     <li><b>删除</b>：收礼人可以删除自己收到的礼物（物理删除）；</li>
 *     <li><b>限流</b>：{@code social-profile-gift} 分类按用户 ID 计数。</li>
 * </ul>
 */
@Component
public final class GiftService {

    /** 限流分类（{@code wikikernel.rate-limit.social-profile-gift.*}）。 */
    public static final String RATE_LIMIT_CATEGORY = "social-profile-gift";

    /** 附言长度上限。 */
    public static final int MESSAGE_LIMIT = 200;

    /** 内置礼物清单（id 稳定，顺序即展示顺序；名称走 {@code socialProfile.gift.<id>}）。 */
    public static final List<String> CATALOG = List.of(
            "star", "heart", "cookie", "rose", "cake", "beer", "trophy", "kitten");

    /** 列表 / 明细的条数上限（默认 200，键 {@code socialProfile.gifts.limit}）。 */
    public static final int DEFAULT_LIST_LIMIT = 200;

    private final UserGiftStore gifts;
    private final UserStore users;
    private final RateLimit rateLimit;
    private final SocialProfileSupport support;
    private final PointsService points;
    private final UserSettings settings;

    public GiftService(UserGiftStore gifts, UserStore users, RateLimit rateLimit,
                       SocialProfileSupport support, PointsService points, UserSettings settings) {
        this.gifts = gifts;
        this.users = users;
        this.rateLimit = rateLimit;
        this.support = support;
        this.points = points;
        this.settings = settings;
    }

    /** 列表条数上限。 */
    public int listLimit() {
        return Math.max(support.setting("gifts.limit", "socialProfile.gifts.limit",
                DEFAULT_LIST_LIMIT), 1);
    }

    /**
     * 上次查看礼物页的时间（用户偏好键 {@code socialProfile.gifts.seenAt}，ISO-8601 时间戳字符串）。
     * 用偏好表而不是新表：只需一个「已读水位」，与「参数设置」的读写走同一条路。
     */
    public java.time.LocalDateTime lastSeenAt(long userId) {
        if (userId <= 0 || settings == null) {
            return null;
        }
        String value = settings.get(userId, SocialProfileKeys.GIFT_SEEN_AT, "");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 记下「刚刚看过礼物页」（顶栏「礼物（N）」徽标的已读水位）。 */
    public void markSeen(long userId) {
        if (userId <= 0 || settings == null) {
            return;
        }
        settings.set(userId, SocialProfileKeys.GIFT_SEEN_AT, java.time.LocalDateTime.now().toString());
    }

    /**
     * 未查看过的新礼物数量（收礼时间晚于上次查看礼物页的时间；从没看过 = 全部算新）。
     */
    public long unseenCount(long userId) {
        if (userId <= 0) {
            return 0;
        }
        java.time.LocalDateTime seen = lastSeenAt(userId);
        long count = 0;
        for (WikiUserGift gift : gifts.received(userId, listLimit())) {
            if (seen == null || (gift.getCreateAt() != null && gift.getCreateAt().isAfter(seen))) {
                count++;
            }
        }
        return count;
    }

    /** 礼物 id 是否在内置清单里。 */
    public boolean isKnown(String giftId) {
        return giftId != null && CATALOG.contains(giftId.trim());
    }

    /** 礼物清单（id + 本地化名称），供送礼物页下拉框用。 */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : CATALOG) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", support.giftName(id));
            rows.add(row);
        }
        return rows;
    }

    /** 某人收到的礼物，新的在前。 */
    public List<WikiUserGift> received(long userId, int limit) {
        return gifts.received(userId, limit);
    }

    /** 某人送出的礼物，新的在前。 */
    public List<WikiUserGift> sent(long userId, int limit) {
        return gifts.sent(userId, limit);
    }

    /** 某人收到的礼物总数。 */
    public long receivedCount(long userId) {
        return receivedCounts(List.of(userId)).getOrDefault(userId, 0L);
    }

    /** 一批用户各自收到的礼物总数。 */
    public Map<Long, Long> receivedCounts(Collection<Long> userIds) {
        return gifts.receivedCounts(userIds);
    }

    /** 某人收到的礼物按种类汇总（种类 id → 件数），按内置清单顺序返回。 */
    public Map<String, Long> receivedByType(long userId) {
        Map<String, Long> counts = gifts.countReceivedByGift(userId);
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String id : CATALOG) {
            Long count = counts.get(id);
            if (count != null && count > 0) {
                ordered.put(id, count);
            }
        }
        // 清单之外的礼物 id（历史上送过、后来从清单删掉）也照常展示
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return ordered;
    }

    /**
     * 送一份礼物。
     *
     * @param giftId  礼物种类（必须在 {@link #CATALOG} 里）
     * @param message 附言（可空；纯文本，超长截断到 {@link #MESSAGE_LIMIT}）
     * @throws GiftException 未登录 / 收件人不存在 / 送给自己 / 礼物种类未知 / 被限流
     */
    public WikiUserGift send(long fromUserId, long toUserId, String giftId, String message) {
        if (fromUserId <= 0) {
            throw new GiftException(GiftException.LOGIN_REQUIRED);
        }
        if (users == null || users.byId(toUserId).isEmpty()) {
            throw new GiftException(GiftException.USER_NOT_FOUND);
        }
        if (Objects.equals(fromUserId, toUserId)) {
            throw new GiftException(GiftException.SELF_NOT_ALLOWED);
        }
        String id = Strings.trimToEmpty(giftId);
        if (!isKnown(id)) {
            throw new GiftException(GiftException.UNKNOWN_GIFT);
        }
        checkRateLimit(fromUserId);
        WikiUserGift gift = new WikiUserGift(null, fromUserId, toUserId, id,
                truncate(Strings.trimToEmpty(message), MESSAGE_LIMIT), LocalDateTime.now());
        WikiUserGift saved = gifts.insert(gift);
        if (points != null) {
            points.award(fromUserId, PointsRules.Action.GIFT);
        }
        return saved;
    }

    /**
     * 删除一份礼物（只有收礼人 / 送礼人可以删；删除人必须是当事人之一）。
     *
     * @throws GiftException 未登录 / 礼物不存在 / 无权删除 / 被限流
     */
    public boolean delete(long actorUserId, long giftRowId) {
        if (actorUserId <= 0) {
            throw new GiftException(GiftException.LOGIN_REQUIRED);
        }
        WikiUserGift gift = gifts.find(giftRowId)
                .orElseThrow(() -> new GiftException(GiftException.NOT_FOUND));
        boolean recipient = Objects.equals(gift.getToUserId(), actorUserId);
        boolean sender = Objects.equals(gift.getFromUserId(), actorUserId);
        if (!recipient && !sender) {
            throw new GiftException(GiftException.FORBIDDEN);
        }
        checkRateLimit(actorUserId);
        gifts.delete(giftRowId);
        return true;
    }

    /** 礼物显示名（{@code socialProfile.gift.<id>}）。 */
    public String giftName(String giftId) {
        return support.giftName(giftId);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private void checkRateLimit(long userId) {
        if (rateLimit == null) {
            return;
        }
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, String.valueOf(userId));
        if (decision.limited()) {
            throw new GiftException(GiftException.RATE_LIMITED, decision.retryAfterSeconds());
        }
        rateLimit.hit(RATE_LIMIT_CATEGORY, String.valueOf(userId));
    }

    /**
     * 礼物操作失败：{@link #getMessage()} 是**消息键**（调用方本地化），
     * {@link #retryAfterSeconds()} 只在 {@link #RATE_LIMITED} 时有意义。
     */
    public static class GiftException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 需要登录。 */
        public static final String LOGIN_REQUIRED = "permissions.loginRequired";
        /** 收礼人不存在。 */
        public static final String USER_NOT_FOUND = "socialProfile.error.userNotFound";
        /** 不能送给自己。 */
        public static final String SELF_NOT_ALLOWED = "socialProfile.error.giftSelf";
        /** 礼物种类未知。 */
        public static final String UNKNOWN_GIFT = "socialProfile.error.giftUnknown";
        /** 礼物不存在。 */
        public static final String NOT_FOUND = "socialProfile.error.giftNotFound";
        /** 无权删除。 */
        public static final String FORBIDDEN = "socialProfile.error.giftForbidden";
        /** 触发限流。 */
        public static final String RATE_LIMITED = "socialProfile.error.rateLimited";

        private final long retryAfterSeconds;

        public GiftException(String messageKey) {
            this(messageKey, 0);
        }

        public GiftException(String messageKey, long retryAfterSeconds) {
            super(messageKey);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
