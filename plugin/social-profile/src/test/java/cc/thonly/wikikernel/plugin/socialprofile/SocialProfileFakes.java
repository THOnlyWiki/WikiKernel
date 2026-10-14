package cc.thonly.wikikernel.plugin.socialprofile;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserAward;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserGift;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserStats;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserAwardStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserBoardStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserGiftStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserRelationStore;
import cc.thonly.wikikernel.plugin.socialprofile.service.UserStatsStore;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 插件测试的内存替身：文案（真实读插件的 {@link SocialProfileMessages}）、功能开关、
 * 用户 / 头像 / 私信数据桥、访问控制、当前用户与 wikitext 渲染；SocialProfile 的
 * 留言板 / 好友 / 礼物 / 积分 / 奖励桥与用户偏好、限流替身也在这里。
 * <p>
 * 替身只模拟「core 数据桥的语义」（上传白名单 / 2MB、私信软删除、好友双向两行等），
 * 用来验证插件把这些规则接到了正确的出口；真实的数据桥实现由 core 的
 * {@code PluginDataBridgeTest} 覆盖。
 */
public final class SocialProfileFakes {

    private SocialProfileFakes() {
    }

    /** 文案：先查 core 的少量键（生产环境由 core 的消息源提供），再查插件的消息包（zh），查不到用兜底文案。 */
    public static MessageSource messages() {
        Map<String, String> zh = new LinkedHashMap<>(coreMessages());
        zh.putAll(new SocialProfileMessages().messages().getOrDefault("zh", Map.of()));
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                String pattern = zh.get(code);
                if (pattern == null) {
                    return defaultMessage;
                }
                return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                String pattern = zh.get(code);
                if (pattern == null) {
                    throw new NoSuchMessageException(code, locale);
                }
                return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    /**
     * core 提供的少量文案键（生产环境里由 core 的消息源解析；插件只带自己的消息包）。
     * 这里放测试真正会用到的几条，值抄自 core 的 {@code messages.properties}。
     */
    private static Map<String, String> coreMessages() {
        Map<String, String> core = new LinkedHashMap<>();
        core.put("permissions.loginRequired", "请先登录。");
        core.put("feature.userInteractionDisabled", "用户交互功能未启用。");
        core.put("feature.userInteractionDisabledHint", "站点已关闭「用户交互」（头像上传 / 私信）。");
        core.put("format.dateTime", "yyyy年MM月dd日 HH:mm");
        core.put("source.login", "登录");
        return core;
    }

    public static SocialProfileSupport support(boolean enabled) {
        return support(enabled, Map.of());
    }

    /** 带插件配置（短键，如 {@code board.pageSize}）的支撑对象。 */
    public static SocialProfileSupport support(boolean enabled, Map<String, String> pluginSettings) {
        return new SocialProfileSupport(config(enabled), messages(),
                PluginContextFake.create(pluginSettings));
    }

    /** 带站点设置覆写（键 → 值，如 {@code socialProfile.points.boardPost}）的支撑对象。 */
    public static SocialProfileSupport supportWithSettings(Map<String, String> siteSettings) {
        return new SocialProfileSupport(config(true, siteSettings), messages(), PluginContextFake.create());
    }

    public static SiteConfig config(boolean enabled) {
        return config(enabled, Map.of());
    }

    /** 站点设置替身：{@code features.social-profile} + 额外键（如 {@code socialProfile.points.*}）。 */
    public static SiteConfig config(boolean enabled, Map<String, String> extra) {
        return config(SocialProfileSupport.ENABLED_KEY, Boolean.toString(enabled), extra);
    }

    /**
     * 站点设置替身：功能开关键写在 {@code enabledKey} 下。
     * 传 {@link SocialProfileSupport#LEGACY_ENABLED_KEY} 可以验证「旧键仍然生效」的兼容逻辑。
     */
    public static SiteConfig config(String enabledKey, String enabledValue, Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>();
        if (extra != null) {
            values.putAll(extra);
        }
        values.put(enabledKey, enabledValue);
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return values.getOrDefault(key, defaultValue);
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                String value = values.get(key);
                return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
            }
        };
    }

    public static Viewer viewer(long id, String name, String... groups) {
        return new Viewer(id, name, id <= 0, Set.of(groups), false);
    }

    public static CurrentUser currentUser(Viewer viewer) {
        return () -> viewer;
    }

    /** 访问控制替身：{@code blockNotice} 非空表示当前用户被封禁。 */
    public static AccessCheck access(String blockNotice) {
        return new AccessCheck() {
            @Override
            public boolean can(AccessAction action, String target, Viewer viewer) {
                return true;
            }

            @Override
            public Optional<String> blockNotice(Viewer viewer) {
                return blockNotice == null || blockNotice.isBlank() ? Optional.empty() : Optional.of(blockNotice);
            }
        };
    }

    /** wikitext 渲染替身：{@code '''粗体'''} → {@code <b>粗体</b>}。 */
    public static WikiText wikiText() {
        return (wikitext, pageTitle) -> bold(wikitext == null ? "" : wikitext);
    }

    private static String bold(String wikitext) {
        int open = wikitext.indexOf("'''");
        if (open < 0) {
            return wikitext;
        }
        int close = wikitext.indexOf("'''", open + 3);
        if (close < 0) {
            return wikitext;
        }
        return wikitext.substring(0, open) + "<b>" + wikitext.substring(open + 3, close) + "</b>"
                + bold(wikitext.substring(close + 3));
    }

    /** 用户表替身（按 ID / 用户名查、批量取名字与头像）。 */
    public static final class FakeUsers implements UserStore {

        private final Map<Long, UserRef> users = new LinkedHashMap<>();

        public FakeUsers add(long id, String username, String avatar) {
            users.put(id, new UserRef(id, username, avatar == null ? "" : avatar, false));
            return this;
        }

        @Override
        public Optional<UserRef> byId(long userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public Optional<UserRef> byName(String username) {
            if (username == null) {
                return Optional.empty();
            }
            return users.values().stream()
                    .filter(ref -> ref.username().equalsIgnoreCase(username.trim()))
                    .findFirst();
        }

        @Override
        public Map<Long, String> names(Collection<Long> userIds) {
            Map<Long, String> names = new LinkedHashMap<>();
            for (Long id : userIds) {
                UserRef ref = users.get(id);
                if (ref != null) {
                    names.put(id, ref.username());
                }
            }
            return names;
        }

        @Override
        public Map<Long, String> avatars(Collection<Long> userIds) {
            Map<Long, String> avatars = new LinkedHashMap<>();
            for (Long id : userIds) {
                UserRef ref = users.get(id);
                if (ref != null) {
                    avatars.put(id, ref.avatar());
                }
            }
            return avatars;
        }

        @Override
        public Set<String> groups(long userId) {
            return Set.of();
        }

        @Override
        public boolean exists(String username) {
            return byName(username).isPresent();
        }

        @Override
        public List<UserRef> randomWithAvatar(int limit) {
            if (limit <= 0) {
                return List.of();
            }
            return users.values().stream()
                    .filter(ref -> !ref.avatar().isEmpty())
                    .limit(limit)
                    .toList();
        }
    }

    /** 头像存储替身：模拟迁移前 core 的校验（白名单 / 2MB / 旧文件替换）与文件解析。 */
    public static final class FakeAvatars implements AvatarStore {

        public final Map<String, byte[]> files = new LinkedHashMap<>();
        public final Map<Long, String> current = new LinkedHashMap<>();
        public final Map<String, Path> paths = new LinkedHashMap<>();
        public long maxBytes = 2L * 1024 * 1024;
        public int storeCalls;
        private int stored;

        @Override
        public String store(long userId, String originalFileName, String contentType, byte[] content) {
            if (userId <= 0 || content == null || content.length == 0) {
                throw new IllegalArgumentException("avatar.empty");
            }
            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            String extension = extensionOf(originalFileName, type);
            if (!type.startsWith("image/") || extension == null) {
                throw new IllegalArgumentException("avatar.notImage");
            }
            if (content.length > maxBytes) {
                throw new IllegalArgumentException("avatar.tooLarge");
            }
            storeCalls++;
            String previous = current.get(userId);
            if (previous != null) {
                files.remove(previous);
            }
            String fileName = "u" + userId + "-" + (++stored) + "." + extension;
            files.put(fileName, content);
            current.put(userId, fileName);
            return url(fileName);
        }

        @Override
        public Optional<Path> resolve(String fileName) {
            return Optional.ofNullable(paths.get(fileName));
        }

        @Override
        public String url(String fileName) {
            return fileName == null || fileName.isBlank() ? UserStore.DEFAULT_AVATAR_URL : "/avatars/" + fileName;
        }

        private static String extensionOf(String originalName, String contentType) {
            String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String extension = name.substring(dot + 1);
                if (ALLOWED_EXTENSIONS.contains(extension)) {
                    return extension;
                }
            }
            return Map.of("image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp")
                    .get(contentType);
        }
    }

    /** 私信表替身（与 core 的 {@code MessageStoreImpl} 语义一致）。 */
    public static final class FakeMessages implements MessageStore {

        public final Map<Long, WikiMessage> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public WikiMessage insert(WikiMessage message) {
            if (message.getId() == null) {
                message.setId(nextId++);
            }
            rows.put(message.getId(), message);
            return message;
        }

        @Override
        public Optional<WikiMessage> find(long messageId) {
            return Optional.ofNullable(rows.get(messageId));
        }

        @Override
        public void update(WikiMessage message) {
            rows.put(message.getId(), message);
        }

        @Override
        public void delete(long messageId) {
            rows.remove(messageId);
        }

        @Override
        public List<WikiMessage> received(long userId, int limit) {
            return limit(sort(rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), userId) && !Boolean.TRUE.equals(row.getToDeleted()))
                    .toList(), false), limit);
        }

        @Override
        public List<WikiMessage> sent(long userId, int limit) {
            return limit(sort(rows.values().stream()
                    .filter(row -> eq(row.getFromUserId(), userId) && !Boolean.TRUE.equals(row.getFromDeleted()))
                    .toList(), false), limit);
        }

        @Override
        public List<WikiMessage> all(long userId, int limit) {
            return limit(sort(rows.values().stream()
                    .filter(row -> (eq(row.getFromUserId(), userId) && !Boolean.TRUE.equals(row.getFromDeleted()))
                            || (eq(row.getToUserId(), userId) && !Boolean.TRUE.equals(row.getToDeleted())))
                    .toList(), true), limit);
        }

        @Override
        public List<WikiMessage> conversation(long userId, long otherUserId, int limit) {
            return limit(sort(rows.values().stream()
                    .filter(row -> (eq(row.getFromUserId(), userId) && eq(row.getToUserId(), otherUserId)
                            && !Boolean.TRUE.equals(row.getFromDeleted()))
                            || (eq(row.getFromUserId(), otherUserId) && eq(row.getToUserId(), userId)
                            && !Boolean.TRUE.equals(row.getToDeleted())))
                    .toList(), true), limit);
        }

        @Override
        public long unreadCount(long userId) {
            return rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), userId)
                            && !Boolean.TRUE.equals(row.getToDeleted())
                            && !Boolean.TRUE.equals(row.getRead()))
                    .count();
        }

        private static boolean eq(Long left, long right) {
            return left != null && left.longValue() == right;
        }

        private static List<WikiMessage> sort(List<WikiMessage> rows, boolean ascending) {
            List<WikiMessage> sorted = new ArrayList<>(rows);
            Comparator<WikiMessage> byId = Comparator.comparing(WikiMessage::getId);
            sorted.sort(ascending ? byId : byId.reversed());
            return sorted;
        }

        private static List<WikiMessage> limit(List<WikiMessage> rows, int limit) {
            return limit > 0 && rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        }
    }

    /** 用户偏好替身（键值表 + 性别列；与 core 的 {@code UserSettingsImpl} 语义一致）。 */
    public static final class FakeSettings implements UserSettings {

        public final Map<Long, Map<String, String>> values = new LinkedHashMap<>();
        public int writes;

        /** 预置一个偏好值（测试用）。 */
        public FakeSettings preset(long userId, String key, String value) {
            values.computeIfAbsent(userId, id -> new LinkedHashMap<>()).put(key, value);
            return this;
        }

        @Override
        public String get(long userId, String key, String defaultValue) {
            Map<String, String> user = values.get(userId);
            String value = user == null ? null : user.get(key);
            return value == null ? defaultValue : value;
        }

        @Override
        public void set(long userId, String key, String value) {
            writes++;
            Map<String, String> user = values.computeIfAbsent(userId, id -> new LinkedHashMap<>());
            user.put(key, value == null ? "" : value);
        }

        @Override
        public Map<String, String> list(long userId, String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            Map<String, String> user = values.get(userId);
            if (user == null) {
                return result;
            }
            for (Map.Entry<String, String> entry : user.entrySet()) {
                if (prefix == null || prefix.isEmpty() || entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }
    }

    /** 限流替身：默认全部放行；{@link #limitedKeys} 里的键第二次起被拒。 */
    public static final class FakeRateLimit implements RateLimit {

        public final Map<String, Integer> hits = new LinkedHashMap<>();
        public final Set<String> limitedKeys = new java.util.LinkedHashSet<>();
        public int blocked;

        @Override
        public Decision check(String category, String key) {
            if (limitedKeys.contains(category + "|" + key)) {
                blocked++;
                return Decision.blocked(30);
            }
            return Decision.allowed();
        }

        @Override
        public void hit(String category, String key) {
            hits.merge(category + "|" + key, 1, Integer::sum);
        }

        @Override
        public void success(String category, String key) {
            hits.remove(category + "|" + key);
        }
    }

    /** 留言板表替身（与 core 的 {@code UserBoardStoreImpl} 语义一致）。 */
    public static final class FakeBoards implements UserBoardStore {

        public final Map<Long, WikiUserBoard> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public WikiUserBoard insert(WikiUserBoard message) {
            if (message.getId() == null) {
                message.setId(nextId++);
            }
            rows.put(message.getId(), message);
            return message;
        }

        @Override
        public Optional<WikiUserBoard> find(long messageId) {
            return Optional.ofNullable(rows.get(messageId));
        }

        @Override
        public void update(WikiUserBoard message) {
            rows.put(message.getId(), message);
        }

        @Override
        public void delete(long messageId) {
            rows.remove(messageId);
        }

        @Override
        public List<WikiUserBoard> received(long toUserId, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), toUserId))
                    .sorted(java.util.Comparator.comparing(WikiUserBoard::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public long count(long toUserId) {
            return rows.values().stream().filter(row -> eq(row.getToUserId(), toUserId)).count();
        }

        @Override
        public List<WikiUserBoard> bySender(long fromUserId, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getFromUserId(), fromUserId))
                    .sorted(java.util.Comparator.comparing(WikiUserBoard::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public List<WikiUserBoard> replies(long parentId, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getParentId(), parentId))
                    .sorted(java.util.Comparator.comparing(WikiUserBoard::getId))
                    .toList(), limit);
        }

        @Override
        public List<WikiUserBoard> recent(int limit) {
            return limit(rows.values().stream()
                    .sorted(java.util.Comparator.comparing(WikiUserBoard::getId).reversed())
                    .toList(), limit);
        }

        private static boolean eq(Long left, long right) {
            return left != null && left.longValue() == right;
        }

        private static List<WikiUserBoard> limit(List<WikiUserBoard> rows, int limit) {
            return limit > 0 && rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        }
    }

    /** 好友 / 仇敌关系表替身（一个方向一行）。 */
    public static final class FakeRelations implements UserRelationStore {

        public final Map<Long, WikiUserRelation> rows = new LinkedHashMap<>();
        private long nextId = 1;

        /** 直接插一行（测试预置数据用）。 */
        public WikiUserRelation add(long from, long to, String type, String status) {
            return insert(new WikiUserRelation(null, from, to, type, status, java.time.LocalDateTime.now()));
        }

        @Override
        public WikiUserRelation insert(WikiUserRelation relation) {
            if (relation.getId() == null) {
                relation.setId(nextId++);
            }
            rows.put(relation.getId(), relation);
            return relation;
        }

        @Override
        public Optional<WikiUserRelation> find(long relationId) {
            return Optional.ofNullable(rows.get(relationId));
        }

        @Override
        public void update(WikiUserRelation relation) {
            rows.put(relation.getId(), relation);
        }

        @Override
        public void delete(long relationId) {
            rows.remove(relationId);
        }

        @Override
        public Optional<WikiUserRelation> find(long fromUserId, long toUserId, String relationType) {
            return rows.values().stream()
                    .filter(row -> eq(row.getFromUserId(), fromUserId) && eq(row.getToUserId(), toUserId)
                            && relationType.equals(row.getRelationType()))
                    .findFirst();
        }

        @Override
        public List<WikiUserRelation> outgoing(long fromUserId, String relationType, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getFromUserId(), fromUserId)
                            && relationType.equals(row.getRelationType()))
                    .sorted(java.util.Comparator.comparing(WikiUserRelation::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public List<WikiUserRelation> incoming(long toUserId, String relationType, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), toUserId)
                            && relationType.equals(row.getRelationType()))
                    .sorted(java.util.Comparator.comparing(WikiUserRelation::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public long pendingFriendCount(long toUserId) {
            return rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), toUserId)
                            && WikiUserRelation.TYPE_FRIEND.equals(row.getRelationType())
                            && WikiUserRelation.STATUS_PENDING.equals(row.getStatus()))
                    .count();
        }

        @Override
        public Map<Long, Long> friendCounts(Collection<Long> userIds) {
            Map<Long, Long> counts = new LinkedHashMap<>();
            for (WikiUserRelation row : rows.values()) {
                if (WikiUserRelation.TYPE_FRIEND.equals(row.getRelationType())
                        && WikiUserRelation.STATUS_ACCEPTED.equals(row.getStatus())
                        && userIds.contains(row.getFromUserId())) {
                    counts.merge(row.getFromUserId(), 1L, Long::sum);
                }
            }
            return counts;
        }

        private static boolean eq(Long left, long right) {
            return left != null && left.longValue() == right;
        }

        private static List<WikiUserRelation> limit(List<WikiUserRelation> rows, int limit) {
            return limit > 0 && rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        }
    }

    /** 礼物表替身。 */
    public static final class FakeGifts implements UserGiftStore {

        public final Map<Long, WikiUserGift> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public WikiUserGift insert(WikiUserGift gift) {
            if (gift.getId() == null) {
                gift.setId(nextId++);
            }
            rows.put(gift.getId(), gift);
            return gift;
        }

        @Override
        public Optional<WikiUserGift> find(long giftId) {
            return Optional.ofNullable(rows.get(giftId));
        }

        @Override
        public void delete(long giftId) {
            rows.remove(giftId);
        }

        @Override
        public List<WikiUserGift> received(long toUserId, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getToUserId(), toUserId))
                    .sorted(java.util.Comparator.comparing(WikiUserGift::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public List<WikiUserGift> sent(long fromUserId, int limit) {
            return limit(rows.values().stream()
                    .filter(row -> eq(row.getFromUserId(), fromUserId))
                    .sorted(java.util.Comparator.comparing(WikiUserGift::getId).reversed())
                    .toList(), limit);
        }

        @Override
        public Map<String, Long> countReceivedByGift(long toUserId) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (WikiUserGift row : rows.values()) {
                if (eq(row.getToUserId(), toUserId)) {
                    counts.merge(row.getGiftId(), 1L, Long::sum);
                }
            }
            return counts;
        }

        @Override
        public Map<Long, Long> receivedCounts(Collection<Long> userIds) {
            Map<Long, Long> counts = new LinkedHashMap<>();
            for (WikiUserGift row : rows.values()) {
                if (userIds.contains(row.getToUserId())) {
                    counts.merge(row.getToUserId(), 1L, Long::sum);
                }
            }
            return counts;
        }

        private static boolean eq(Long left, long right) {
            return left != null && left.longValue() == right;
        }

        private static List<WikiUserGift> limit(List<WikiUserGift> rows, int limit) {
            return limit > 0 && rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        }
    }

    /** 积分 / 等级表替身（含排行榜的用户名 / 头像补齐）。 */
    public static final class FakeStats implements UserStatsStore {

        public final Map<Long, WikiUserStats> rows = new LinkedHashMap<>();
        private final FakeUsers users;
        private long nextId = 1;

        public FakeStats(FakeUsers users) {
            this.users = users;
        }

        @Override
        public WikiUserStats addPoints(long userId, long delta) {
            if (userId <= 0) {
                return null;
            }
            WikiUserStats row = rows.get(userId);
            if (row == null) {
                if (delta <= 0) {
                    return null;
                }
                row = new WikiUserStats(nextId++, userId, delta, 1, java.time.LocalDateTime.now());
                rows.put(userId, row);
                return row;
            }
            row.setPoints(Math.max((row.getPoints() == null ? 0 : row.getPoints()) + delta, 0));
            row.setUpdateAt(java.time.LocalDateTime.now());
            return row;
        }

        @Override
        public WikiUserStats setPoints(long userId, long points, int level) {
            WikiUserStats row = rows.get(userId);
            if (row == null) {
                row = new WikiUserStats(nextId++, userId, Math.max(points, 0), Math.max(level, 1),
                        java.time.LocalDateTime.now());
                rows.put(userId, row);
                return row;
            }
            row.setPoints(Math.max(points, 0));
            row.setLevel(Math.max(level, 1));
            row.setUpdateAt(java.time.LocalDateTime.now());
            return row;
        }

        @Override
        public Optional<WikiUserStats> byUser(long userId) {
            return Optional.ofNullable(rows.get(userId));
        }

        @Override
        public Map<Long, WikiUserStats> byUsers(Collection<Long> userIds) {
            Map<Long, WikiUserStats> result = new LinkedHashMap<>();
            for (Long id : userIds) {
                WikiUserStats row = rows.get(id);
                if (row != null) {
                    result.put(id, row);
                }
            }
            return result;
        }

        @Override
        public List<Entry> top(int offset, int limit) {
            List<WikiUserStats> sorted = new ArrayList<>(rows.values());
            sorted.sort(java.util.Comparator
                    .comparingLong((WikiUserStats row) -> row.getPoints() == null ? 0 : row.getPoints())
                    .reversed()
                    .thenComparingLong(WikiUserStats::getUserId));
            List<Entry> entries = new ArrayList<>();
            for (int i = Math.max(offset, 0); i < sorted.size(); i++) {
                if (limit > 0 && entries.size() >= limit) {
                    break;
                }
                WikiUserStats row = sorted.get(i);
                String name = users.byId(row.getUserId()).map(UserStore.UserRef::username).orElse("");
                String avatar = users.byId(row.getUserId())
                        .map(ref -> ref.avatar().isEmpty() ? UserStore.DEFAULT_AVATAR_URL
                                : "/avatars/" + ref.avatar())
                        .orElse(UserStore.DEFAULT_AVATAR_URL);
                entries.add(new Entry(row.getUserId(), name, avatar,
                        row.getPoints() == null ? 0 : row.getPoints(),
                        row.getLevel() == null ? 1 : row.getLevel()));
            }
            return entries;
        }

        @Override
        public long count() {
            return rows.size();
        }
    }

    /** 奖励 / 勋章表替身。 */
    public static final class FakeAwards implements UserAwardStore {

        public final Map<Long, WikiUserAward> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public WikiUserAward insert(WikiUserAward award) {
            if (award.getId() == null) {
                award.setId(nextId++);
            }
            rows.put(award.getId(), award);
            return award;
        }

        @Override
        public Optional<WikiUserAward> find(long awardRowId) {
            return Optional.ofNullable(rows.get(awardRowId));
        }

        @Override
        public void delete(long awardRowId) {
            rows.remove(awardRowId);
        }

        @Override
        public List<WikiUserAward> byUser(long userId, int limit) {
            List<WikiUserAward> result = rows.values().stream()
                    .filter(row -> row.getUserId() != null && row.getUserId() == userId)
                    .sorted(java.util.Comparator.comparing(WikiUserAward::getId).reversed())
                    .toList();
            return limit > 0 && result.size() > limit ? List.copyOf(result.subList(0, limit)) : result;
        }

        @Override
        public boolean has(long userId, String awardId) {
            return rows.values().stream().anyMatch(row -> row.getUserId() != null && row.getUserId() == userId
                    && awardId.equals(row.getAwardId()));
        }

        @Override
        public Map<Long, Long> counts(Collection<Long> userIds) {
            Map<Long, Long> counts = new LinkedHashMap<>();
            for (WikiUserAward row : rows.values()) {
                if (userIds.contains(row.getUserId())) {
                    counts.merge(row.getUserId(), 1L, Long::sum);
                }
            }
            return counts;
        }

        @Override
        public List<String> awardIdsOf(long userId) {
            List<String> ids = new ArrayList<>();
            for (WikiUserAward row : byUser(userId, 0)) {
                if (!ids.contains(row.getAwardId())) {
                    ids.add(row.getAwardId());
                }
            }
            return ids;
        }
    }
}
