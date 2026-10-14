package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterStore;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.BlockWriter;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.UserWriter;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterServiceImpl;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试替身：内存版的 {@link AbuseFilterStore} 与 {@code common.service} 各桥，以及站点设置 / 文案源。
 * 全部不依赖 Spring 与 core（插件测试只跑插件自己的代码）。
 */
public final class AbuseFilterFakes {

    private AbuseFilterFakes() {
    }

    /** 功能开关替身：只认 {@code features.abuse-filter}。 */
    public static SiteConfig config(boolean enabled) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return AbuseFilterSupport.ENABLED_KEY.equals(key) ? enabled : defaultValue;
            }
        };
    }

    /**
     * 文案源替身：先查插件自己的 {@code i18n/messages.properties}（默认语言，等价于 core 的
     * {@code PluginMessageSource} 查插件消息包的那一段），查不到再返回兜底文案（没有兜底就返回键名），
     * 并按 {@link MessageFormat} 填参（与 core 的 MessageSource 行为一致）。
     * <p>
     * 文案已经搬到插件资源文件里，所以测试不再依赖生产代码里的中文兜底。
     */
    public static MessageSource messages() {
        Map<String, String> bundle = new AbuseFilterMessages().messages().getOrDefault("zh", Map.of());
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                String text = bundle.get(code);
                if (text == null) {
                    text = defaultMessage == null ? code : defaultMessage;
                }
                return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return code;
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable,
                                     Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    public static UserStore userStore(Map<Long, String> names, Map<Long, Set<String>> groups) {
        return new UserStore() {
            @Override
            public Optional<UserRef> byId(long userId) {
                String name = names.get(userId);
                return name == null ? Optional.empty()
                        : Optional.of(new UserRef(userId, name, "", true));
            }

            @Override
            public Optional<UserRef> byName(String username) {
                return names.entrySet().stream()
                        .filter(entry -> entry.getValue().equalsIgnoreCase(username))
                        .findFirst()
                        .map(entry -> new UserRef(entry.getKey(), entry.getValue(), "", true));
            }

            @Override
            public Map<Long, String> names(Collection<Long> userIds) {
                Map<Long, String> result = new LinkedHashMap<>();
                for (Long id : userIds) {
                    String name = names.get(id);
                    if (name != null) {
                        result.put(id, name);
                    }
                }
                return result;
            }

            @Override
            public Map<Long, String> avatars(Collection<Long> userIds) {
                return Map.of();
            }

            @Override
            public Set<String> groups(long userId) {
                return groups.getOrDefault(userId, Set.of());
            }

            @Override
            public boolean exists(String username) {
                return names.containsValue(username);
            }

            @Override
            public List<UserRef> randomWithAvatar(int limit) {
                return List.of();
            }
        };
    }

    /** 用户写入桥替身：记录系统账号创建与用户组移除。 */
    public static final class FakeUserWriter implements UserWriter {

        public final Map<String, String> systemAccounts = new LinkedHashMap<>();
        public final List<String> revoked = new ArrayList<>();

        @Override
        public boolean ensureSystemAccount(String username, String groupName) {
            if (systemAccounts.containsKey(username)) {
                return false;
            }
            systemAccounts.put(username, groupName);
            return true;
        }

        @Override
        public boolean revokeGroup(long userId, String groupName) {
            revoked.add(userId + "|" + groupName);
            return true;
        }
    }

    /** 封禁写入桥替身：记录封禁目标 / 原因。 */
    public static final class FakeBlockWriter implements BlockWriter {

        public final List<String> blocks = new ArrayList<>();

        @Override
        public boolean block(String actorUsername, String target, LocalDateTime expiresAt, String reason) {
            blocks.add(actorUsername + "|" + target + "|" + reason);
            return true;
        }
    }

    public static AccessCheck accessCheck(boolean blocked) {
        return new AccessCheck() {
            @Override
            public boolean can(AccessAction action, String target,
                               Viewer viewer) {
                return true;
            }

            @Override
            public Optional<String> blockNotice(Viewer viewer) {
                return blocked ? Optional.of("已被封禁") : Optional.empty();
            }
        };
    }

    /** 页面桥替身：只认识预置的页面与正文。 */
    public static WikiPageStore pageStore(Map<String, Long> pageIds, Map<Long, String> contents) {
        return new WikiPageStore() {
            @Override
            public Optional<PageSnapshot> byId(long pageId) {
                return pageIds.entrySet().stream()
                        .filter(entry -> entry.getValue() == pageId)
                        .findFirst()
                        .map(entry -> new PageSnapshot(pageId, entry.getKey(), false, true,
                                Strings.trimToEmpty(contents.get(pageId)).length(), null));
            }

            @Override
            public Optional<PageSnapshot> byTitle(String title) {
                Long id = pageIds.get(title);
                return id == null ? Optional.empty() : byId(id);
            }

            @Override
            public Optional<String> content(long pageId) {
                return Optional.ofNullable(contents.get(pageId));
            }

            @Override
            public List<PageSnapshot> contentPages() {
                return List.of();
            }
        };
    }

    /** 内存版滥用过滤器数据桥。 */
    public static final class InMemoryStore implements AbuseFilterStore {

        public final Map<Long, WikiAbuseFilter> filters = new LinkedHashMap<>();
        public final List<WikiAbuseFilterRevision> revisions = new ArrayList<>();
        public final Map<Long, WikiAbuseLog> logs = new LinkedHashMap<>();
        public final List<RecentEdit> edits = new ArrayList<>();
        public final Map<Long, UserStats> stats = new LinkedHashMap<>();
        public final AtomicLong filterIds = new AtomicLong();
        public final AtomicLong revisionIds = new AtomicLong();
        public final AtomicLong logIds = new AtomicLong();

        @Override
        public List<WikiAbuseFilter> filters(boolean includeHidden) {
            return filters.values().stream()
                    .filter(filter -> includeHidden || !Boolean.TRUE.equals(filter.getHidden()))
                    .sorted(Comparator.comparing(WikiAbuseFilter::getId).reversed())
                    .toList();
        }

        @Override
        public Optional<WikiAbuseFilter> find(long filterId) {
            return Optional.ofNullable(filters.get(filterId));
        }

        @Override
        public long save(WikiAbuseFilter filter) {
            if (filter.getId() == null) {
                filter.setId(filterIds.incrementAndGet());
            }
            filters.put(filter.getId(), filter);
            return filter.getId();
        }

        @Override
        public boolean updateFlags(long filterId, Boolean enabled, Boolean deleted, Boolean hidden) {
            WikiAbuseFilter filter = filters.get(filterId);
            if (filter == null) {
                return false;
            }
            if (enabled != null) {
                filter.setEnabled(enabled);
            }
            if (deleted != null) {
                filter.setDeleted(deleted);
            }
            if (hidden != null) {
                filter.setHidden(hidden);
            }
            return true;
        }

        @Override
        public void bumpHits(long filterId) {
            WikiAbuseFilter filter = filters.get(filterId);
            if (filter != null) {
                filter.setHits((filter.getHits() == null ? 0 : filter.getHits()) + 1);
            }
        }

        @Override
        public boolean delete(long filterId) {
            if (filters.remove(filterId) == null) {
                return false;
            }
            revisions.removeIf(revision -> revision.getFilterId() == filterId);
            return true;
        }

        @Override
        public void insertRevision(WikiAbuseFilterRevision revision) {
            revision.setId(revisionIds.incrementAndGet());
            revisions.add(revision);
        }

        @Override
        public List<WikiAbuseFilterRevision> history(long filterId) {
            return revisions.stream()
                    .filter(revision -> revision.getFilterId() == filterId)
                    .sorted(Comparator.comparing(WikiAbuseFilterRevision::getId).reversed())
                    .toList();
        }

        @Override
        public Optional<WikiAbuseFilterRevision> findRevision(long revisionId) {
            return revisions.stream().filter(revision -> revision.getId() == revisionId).findFirst();
        }

        @Override
        public void insertLog(WikiAbuseLog entry) {
            entry.setId(logIds.incrementAndGet());
            logs.put(entry.getId(), entry);
        }

        @Override
        public List<WikiAbuseLog> logs(boolean includeHided, int limit) {
            return logs.values().stream()
                    .filter(entry -> includeHided || !Boolean.TRUE.equals(entry.getHided()))
                    .sorted(Comparator.comparing(WikiAbuseLog::getId).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<WikiAbuseLog> findLog(long logId) {
            return Optional.ofNullable(logs.get(logId));
        }

        @Override
        public boolean hideLog(long logId, boolean hided) {
            WikiAbuseLog entry = logs.get(logId);
            if (entry == null) {
                return false;
            }
            entry.setHided(hided);
            return true;
        }

        @Override
        public List<RecentEdit> recentEdits(int limit) {
            return edits.stream().limit(limit).toList();
        }

        @Override
        public Optional<UserStats> userStats(long userId) {
            return Optional.ofNullable(stats.get(userId));
        }
    }

    /** 一个可用的插件支撑 + 服务组合（各测试按需改 fake 里的状态）。 */
    public static final class Fixture {

        public final InMemoryStore store = new InMemoryStore();
        public final Map<Long, String> names = new LinkedHashMap<>();
        public final Map<Long, Set<String>> groups = new LinkedHashMap<>();
        public final FakeUserWriter userWriter = new FakeUserWriter();
        public final FakeBlockWriter blockWriter = new FakeBlockWriter();
        public final Map<String, Long> pageIds = new LinkedHashMap<>();
        public final Map<Long, String> pageContents = new LinkedHashMap<>();
        public final Map<Long, LocalDateTime> registeredAt = new LinkedHashMap<>();
        public boolean blocked;
        public boolean enabled = true;

        private AbuseFilterSupport support;
        private AbuseFilterService service;

        /** 支撑（读 {@code enabled} 字段的实时值，测试里可以随时切换开关）。 */
        public AbuseFilterSupport support() {
            if (support == null) {
                SiteConfig config = new SiteConfig() {
                    @Override
                    public String get(String key, String defaultValue) {
                        return defaultValue;
                    }

                    @Override
                    public boolean getBoolean(String key, boolean defaultValue) {
                        return AbuseFilterSupport.ENABLED_KEY.equals(key) ? enabled : defaultValue;
                    }
                };
                support = new AbuseFilterSupport(config, messages());
            }
            return support;
        }

        /** 服务（同一 fixture 只建一次，节流计数 / 系统账号标记等状态在测试内共享）。 */
        public AbuseFilterService service() {
            if (service == null) {
                service = new AbuseFilterServiceImpl(store, userStore(), userWriter, blockWriter,
                        accessCheck(blocked), support());
            }
            return service;
        }

        public InMemoryStore store() {
            return store;
        }

        public UserStore userStore() {
            return AbuseFilterFakes.userStore(names, groups);
        }

        public WikiPageStore pageStore() {
            return AbuseFilterFakes.pageStore(pageIds, pageContents);
        }

        /** 注册一个用户（供 user_* 变量使用）。 */
        public long addUser(String username, String... userGroups) {
            long id = names.size() + 1L;
            names.put(id, username);
            groups.put(id, new LinkedHashSet<>(List.of(userGroups)));
            return id;
        }

        /** 登记一个页面（供 page_id / old_wikitext 变量使用）。 */
        public long addPage(String title, String content) {
            long id = pageIds.size() + 1L;
            pageIds.put(title, id);
            pageContents.put(id, content);
            return id;
        }

        /** 切换功能开关（同一 fixture 里复用服务实例时用）。 */
        public void enabled(boolean value) {
            this.enabled = value;
        }
    }
}
