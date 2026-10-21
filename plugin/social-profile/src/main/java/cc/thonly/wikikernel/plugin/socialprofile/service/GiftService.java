package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiGift;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.model.SocialProfileKeys;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

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

    /** 内置礼物图标。 */
    private static final Map<String, String> BUILTIN_ICONS = Map.of(
            "star", "⭐", "heart", "❤️", "cookie", "🍪", "rose", "🌹",
            "cake", "🎂", "beer", "🍺", "trophy", "🏆", "kitten", "🐱");

    /** 自定义礼物名称 / 说明 / 图标长度上限。 */
    public static final int NAME_LIMIT = 80;
    public static final int DESCRIPTION_LIMIT = 255;
    public static final int ICON_LIMIT = 16;

    /** 列表 / 明细的条数上限（默认 200，键 {@code socialProfile.gifts.limit}）。 */
    public static final int DEFAULT_LIST_LIMIT = 200;

    private final UserGiftStore gifts;
    private final UserStore users;
    private final RateLimit rateLimit;
    private final SocialProfileSupport support;
    private final PointsService points;
    private final UserSettings settings;

    /** 自定义礼物目录；独立单元测试可以没有 Spring，缺少时只使用内置礼物。 */
    @Autowired(required = false)
    private GiftCatalogStore catalogStore;

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

    /** 礼物 id 是否可用（内置或启用的自定义礼物）。 */
    public boolean isKnown(String giftId) {
        String id = Strings.trimToEmpty(giftId);
        if (id.isEmpty()) {
            return false;
        }
        if (CATALOG.contains(id)) {
            return true;
        }
        return catalogStore != null && catalogStore.findByKey(id)
                .map(gift -> Boolean.TRUE.equals(gift.getEnabled()))
                .orElse(false);
    }

    /** 礼物清单（id + 本地化名称 + 说明 + 图标），供送礼物页卡片用。 */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : CATALOG) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", support.giftName(id));
            row.put("description", support.giftDescription(id));
            row.put("icon", BUILTIN_ICONS.getOrDefault(id, "🎁"));
            row.put("custom", false);
            rows.add(row);
        }
        if (catalogStore != null) {
            for (WikiGift gift : catalogStore.all()) {
                if (!Boolean.TRUE.equals(gift.getEnabled())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", gift.getGiftKey());
                row.put("name", gift.getName());
                row.put("description", gift.getDescription());
                row.put("icon", Strings.trimToEmpty(gift.getIcon()).isEmpty() ? "🎁" : gift.getIcon());
                row.put("custom", true);
                rows.add(row);
            }
        }
        return rows;
    }

    /** 自定义礼物列表（管理页用；包含已停用的）。 */
    public List<WikiGift> customGifts() {
        return catalogStore == null ? List.of() : catalogStore.all();
    }

    /** 管理页的一行：附带「当前用户能不能停用/启用这份礼物」。 */
    public List<Map<String, Object>> managerRows(long actorId, boolean admin) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiGift gift : customGifts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", gift.getId());
            row.put("key", gift.getGiftKey());
            row.put("name", gift.getName());
            row.put("description", gift.getDescription());
            row.put("icon", Strings.trimToEmpty(gift.getIcon()).isEmpty() ? "🎁" : gift.getIcon());
            row.put("enabled", Boolean.TRUE.equals(gift.getEnabled()));
            row.put("canManage", admin || Objects.equals(gift.getCreatedBy(), actorId));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 创建自定义礼物。任何登录用户都可以创建；礼物键由服务端生成，不能覆盖内置礼物。
     *
     * @throws GiftException 未登录 / 名称为空或超长 / 说明或图标超长 / 目录服务不可用
     */
    public WikiGift createGift(long actorId, String name, String description, String icon) {
        if (actorId <= 0) {
            throw new GiftException(GiftException.LOGIN_REQUIRED);
        }
        if (catalogStore == null) {
            throw new GiftException(GiftException.CATALOG_UNAVAILABLE);
        }
        String cleanName = Strings.trimToEmpty(name);
        if (cleanName.isEmpty()) {
            throw new GiftException(GiftException.NAME_REQUIRED);
        }
        if (cleanName.length() > NAME_LIMIT) {
            throw new GiftException(GiftException.NAME_TOO_LONG);
        }
        String cleanDescription = truncate(Strings.trimToEmpty(description), DESCRIPTION_LIMIT);
        String cleanIcon = truncate(Strings.trimToEmpty(icon), ICON_LIMIT);
        if (cleanIcon.isEmpty()) {
            cleanIcon = "🎁";
        }
        String key;
        do {
            key = "custom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        } while (CATALOG.contains(key) || catalogStore.findByKey(key).isPresent());
        WikiGift gift = new WikiGift(null, key, cleanName, cleanDescription, cleanIcon,
                actorId, Boolean.TRUE, LocalDateTime.now());
        return catalogStore.insert(gift);
    }

    /**
     * 启用 / 停用自定义礼物。创建者本人或管理员可以操作。
     *
     * @throws GiftException 未登录 / 不存在 / 无权限 / 目录服务不可用
     */
    public boolean setGiftEnabled(long actorId, boolean admin, long id, boolean enabled) {
        if (actorId <= 0) {
            throw new GiftException(GiftException.LOGIN_REQUIRED);
        }
        if (catalogStore == null) {
            throw new GiftException(GiftException.CATALOG_UNAVAILABLE);
        }
        WikiGift gift = catalogStore.find(id)
                .orElseThrow(() -> new GiftException(GiftException.NOT_FOUND));
        if (!admin && !Objects.equals(gift.getCreatedBy(), actorId)) {
            throw new GiftException(GiftException.MANAGER_FORBIDDEN);
        }
        catalogStore.setEnabled(id, enabled);
        return true;
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

    /** 某人收到的礼物按种类汇总（种类 id → 件数），按内置清单 + 自定义礼物顺序返回。 */
    public Map<String, Long> receivedByType(long userId) {
        Map<String, Long> counts = gifts.countReceivedByGift(userId);
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String id : CATALOG) {
            Long count = counts.get(id);
            if (count != null && count > 0) {
                ordered.put(id, count);
            }
        }
        for (WikiGift gift : customGifts()) {
            Long count = counts.get(gift.getGiftKey());
            if (count != null && count > 0) {
                ordered.put(gift.getGiftKey(), count);
            }
        }
        // 清单之外的礼物 id（历史上送过、后来停用）也照常展示
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

    /** 礼物显示名（自定义礼物优先数据库名，内置礼物走 {@code socialProfile.gift.<id>}）。 */
    public String giftName(String giftId) {
        String id = Strings.trimToEmpty(giftId);
        if (catalogStore != null) {
            Optional<WikiGift> custom = catalogStore.findByKey(id);
            if (custom.isPresent() && !Strings.trimToEmpty(custom.get().getName()).isEmpty()) {
                return custom.get().getName();
            }
        }
        return support.giftName(id);
    }

    /** 礼物说明（内置走 {@code socialProfile.gift.desc.<id>}，自定义读取数据库）。 */
    public String giftDescription(String giftId) {
        String id = Strings.trimToEmpty(giftId);
        if (catalogStore != null) {
            Optional<WikiGift> custom = catalogStore.findByKey(id);
            if (custom.isPresent()) {
                return custom.get().getDescription();
            }
        }
        return support.giftDescription(id);
    }

    /** 礼物图标（内置固定 emoji，自定义读取数据库）。 */
    public String giftIcon(String giftId) {
        String id = Strings.trimToEmpty(giftId);
        if (catalogStore != null) {
            Optional<WikiGift> custom = catalogStore.findByKey(id);
            if (custom.isPresent() && !Strings.trimToEmpty(custom.get().getIcon()).isEmpty()) {
                return custom.get().getIcon();
            }
        }
        return BUILTIN_ICONS.getOrDefault(id, "🎁");
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
        /** 自定义礼物目录不可用。 */
        public static final String CATALOG_UNAVAILABLE = "socialProfile.error.giftCatalogUnavailable";
        /** 礼物名称为空。 */
        public static final String NAME_REQUIRED = "socialProfile.error.giftNameRequired";
        /** 礼物名称过长。 */
        public static final String NAME_TOO_LONG = "socialProfile.error.giftNameTooLong";
        /** 无权限管理这份礼物。 */
        public static final String MANAGER_FORBIDDEN = "socialProfile.error.giftManagerForbidden";
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
