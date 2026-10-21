package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterStore;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.BlockWriter;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.UserWriter;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 滥用过滤器实现（迁移前是 core 的 {@code AbuseFilterServiceImpl}）：
 * 条件求值（{@link AbuseCondition}）+ 操作执行 + 节流 + 滥用日志 + 测试。
 * <p>
 * 与迁移前的区别只在**依赖来源**：数据读写走 {@code common.service.AbuseFilterStore}，
 * 用户 / 权限 / 封禁走 {@code common.service} 的桥（{@code UserStore} / {@code UserWriter} /
 * {@code BlockWriter} / {@code AccessCheck}），站点名与开关走 {@code SiteConfig}。
 * 操作语义与迁移前完全一致：
 * <ul>
 *     <li>{@code disallow}：阻止本次操作，并把过滤器说明作为原因；</li>
 *     <li>{@code warn} / {@code degrade}：给出警告（网页要求再次确认，API / AI 只提示）；</li>
 *     <li>{@code tag}：给版本打标签（过滤器设置了标签则用标签，否则 {@code abusefilter-编号}）；</li>
 *     <li>{@code throttle}：设置了节流时，**其他操作只在超过节流限制后执行**（仿 MediaWiki）；</li>
 *     <li>{@code block}：以系统账号「滥用过滤器」封禁触发者 3 天；</li>
 *     <li>{@code blockautopromote}：移除触发者的「自动确定用户」组。</li>
 * </ul>
 * 每次命中都会写入滥用日志（变量快照为 JSON）。
 */
@Component
public class AbuseFilterServiceImpl implements AbuseFilterService {

    private static final int MAX_TEST_LIMIT = 500;
    private static final int CONDITION_CACHE_SIZE = 256;
    private static final int BLOCK_DAYS = 3;

    private final AbuseFilterStore store;
    private final UserStore userStore;
    private final UserWriter userWriter;
    private final BlockWriter blockWriter;
    private final AccessCheck accessCheck;
    private final AbuseFilterSupport support;

    /** 警告 / 阻止信息页读取（可选：单元测试没有 Spring 时为空，直接使用说明文案）。 */
    @Autowired(required = false)
    private WikiPageStore pageStore;

    /** 已编译条件的 LRU 缓存（内容 → 条件；过滤器保存后清空）。 */
    private final Map<String, AbuseCondition> conditionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AbuseCondition> eldest) {
                    return size() > CONDITION_CACHE_SIZE;
                }
            });

    /** 节流计数：键 = 过滤器编号 + 分组值，值 = 命中时间戳。 */
    private final Map<String, ArrayDeque<Long>> throttleCounters = new ConcurrentHashMap<>();

    /** 系统账号「兜底创建」只做一次（避免每次求值都查库）。 */
    private final AtomicBoolean systemAccountChecked = new AtomicBoolean(false);

    public AbuseFilterServiceImpl(AbuseFilterStore store,
                                  UserStore userStore,
                                  UserWriter userWriter,
                                  BlockWriter blockWriter,
                                  AccessCheck accessCheck,
                                  AbuseFilterSupport support) {
        this.store = store;
        this.userStore = userStore;
        this.userWriter = userWriter;
        this.blockWriter = blockWriter;
        this.accessCheck = accessCheck;
        this.support = support;
    }

    /** 插件文案（键名与迁移前 core 一致）。 */
    String message(String key, Object... args) {
        return support.message(key, key, args);
    }

    @Override
    public boolean enabled() {
        return support.enabled();
    }

    @Override
    public boolean ensureSystemAccount() {
        if (!enabled()) {
            return false;
        }
        // UserWriter 自己判断账号是否存在（已存在时不动它的密码与权限）
        return userWriter != null && userWriter.ensureSystemAccount(SYSTEM_ACCOUNT, SYSTEM_ACCOUNT_GROUP);
    }

    /** 启用状态下第一次求值时兜底创建系统账号（过滤器封禁以它记录）。 */
    private void ensureSystemAccountOnce() {
        if (systemAccountChecked.compareAndSet(false, true)) {
            try {
                ensureSystemAccount();
            } catch (RuntimeException exception) {
                // 账号创建失败不影响过滤：封禁操作会记在「无操作者」上
                systemAccountChecked.set(false);
            }
        }
    }

    // ==================== 过滤器 CRUD ====================

    @Override
    public List<WikiAbuseFilter> filters(boolean includeHidden) {
        return store.filters(includeHidden);
    }

    @Override
    public Optional<WikiAbuseFilter> find(long id) {
        return store.find(id);
    }

    @Override
    public long save(long id, FilterForm form, Long actorId) {
        String pattern = Strings.trimToEmpty(form.pattern());
        // 语法检查（未知变量 / 函数 / 括号不匹配都会抛 SyntaxException）
        AbuseCondition.compile(pattern);
        String actions = normalizeActions(form.actions());
        int throttleCount = Math.max(0, form.throttleCount());
        int throttlePeriod = Math.max(0, form.throttlePeriod());
        if (actions.contains("throttle") && (throttleCount < 1 || throttlePeriod < 1)) {
            throw new IllegalArgumentException(message("abusefilter.throttleInvalid"));
        }
        LocalDateTime now = LocalDateTime.now();
        WikiAbuseFilter filter;
        if (id > 0) {
            filter = store.find(id).orElse(null);
            if (filter == null) {
                throw new IllegalArgumentException(message("abusefilter.notFound"));
            }
        } else {
            filter = new WikiAbuseFilter();
            filter.setHits(0);
            filter.setCreateAt(now);
        }
        filter.setDescription(trim(form.description()));
        filter.setPattern(pattern);
        filter.setActions(actions);
        filter.setTags(trim(form.tags()));
        filter.setThrottleCount(throttleCount);
        filter.setThrottlePeriod(throttlePeriod);
        filter.setThrottleGroup(normalizeGroup(form.throttleGroup()));
        filter.setEnabled(form.enabled());
        filter.setDeleted(form.deleted());
        filter.setHidden(form.hidden());
        filter.setNotes(trim(form.notes()));
        filter.setWarnMessage(trim(form.warnMessage()));
        filter.setWarnPage(AbuseFilterSupport.messagePageTitle(form.warnPage()));
        filter.setDisallowMessage(trim(form.disallowMessage()));
        filter.setDisallowPage(AbuseFilterSupport.messagePageTitle(form.disallowPage()));
        filter.setBlockTalk(form.blockTalk());
        filter.setBlockAnonDays(Math.max(0, form.blockAnonDays()));
        filter.setBlockUserDays(Math.max(0, form.blockUserDays()));
        filter.setLastEditorId(actorId);
        filter.setLastEditAt(now);
        long filterId = store.save(filter);

        WikiAbuseFilterRevision revision = new WikiAbuseFilterRevision();
        revision.setFilterId(filterId);
        revision.setDescription(filter.getDescription());
        revision.setPattern(filter.getPattern());
        revision.setActions(filter.getActions());
        revision.setTags(filter.getTags());
        revision.setThrottleCount(filter.getThrottleCount());
        revision.setThrottlePeriod(filter.getThrottlePeriod());
        revision.setThrottleGroup(filter.getThrottleGroup());
        revision.setEnabled(filter.getEnabled());
        revision.setDeleted(filter.getDeleted());
        revision.setHidden(filter.getHidden());
        revision.setNotes(filter.getNotes());
        revision.setWarnMessage(filter.getWarnMessage());
        revision.setWarnPage(filter.getWarnPage());
        revision.setDisallowMessage(filter.getDisallowMessage());
        revision.setDisallowPage(filter.getDisallowPage());
        revision.setBlockTalk(filter.getBlockTalk());
        revision.setBlockAnonDays(filter.getBlockAnonDays());
        revision.setBlockUserDays(filter.getBlockUserDays());
        revision.setUserId(actorId);
        revision.setComment(trim(form.comment()));
        revision.setCreateAt(now);
        store.insertRevision(revision);
        conditionCache.clear();
        return filterId;
    }

    @Override
    public List<WikiAbuseFilterRevision> history(long filterId) {
        return store.history(filterId);
    }

    @Override
    public boolean restore(long filterId, long revisionId, Long actorId) {
        WikiAbuseFilterRevision revision = store.findRevision(revisionId).orElse(null);
        if (revision == null || !revision.getFilterId().equals(filterId)) {
            return false;
        }
        save(filterId, new FilterForm(revision.getDescription(), revision.getPattern(),
                revision.getActions(), revision.getTags(), Boolean.TRUE.equals(revision.getEnabled()),
                Boolean.TRUE.equals(revision.getDeleted()), Boolean.TRUE.equals(revision.getHidden()),
                revision.getThrottleCount() == null ? 0 : revision.getThrottleCount(),
                revision.getThrottlePeriod() == null ? 0 : revision.getThrottlePeriod(),
                revision.getThrottleGroup(), "恢复历史版本 #" + revisionId,
                revision.getNotes(), revision.getWarnMessage(), revision.getWarnPage(),
                revision.getDisallowMessage(), revision.getDisallowPage(),
                Boolean.TRUE.equals(revision.getBlockTalk()),
                revision.getBlockAnonDays() == null ? 3 : revision.getBlockAnonDays(),
                revision.getBlockUserDays() == null ? 3 : revision.getBlockUserDays()), actorId);
        return true;
    }

    // ==================== 求值 ====================

    @Override
    public Outcome evaluate(String action, Viewer viewer, String ip, Map<String, Object> variables) {
        if (!enabled()) {
            return Outcome.none();
        }
        ensureSystemAccountOnce();
        AbuseContext context = baseContext(viewer, ip);
        if (variables != null) {
            variables.forEach(context::put);
        }
        context.put("action", Strings.trimToEmpty(action));
        return run(viewer, ip, context);
    }

    private Outcome run(Viewer viewer, String ip, AbuseContext context) {
        boolean disallowed = false;
        List<String> messages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        List<Long> matched = new ArrayList<>();
        for (WikiAbuseFilter filter : filters(true)) {
            if (!Boolean.TRUE.equals(filter.getEnabled()) || Boolean.TRUE.equals(filter.getDeleted())) {
                continue;
            }
            boolean hit;
            try {
                hit = conditionOf(filter.getPattern()).test(context);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!hit) {
                continue;
            }
            matched.add(filter.getId());
            store.bumpHits(filter.getId());
            List<String> actions = actionsOf(filter);
            boolean throttleRequired = actions.contains("throttle");
            boolean applyOthers = !throttleRequired || throttleTripped(filter, context, viewer, ip);
            List<String> applied = new ArrayList<>();
            if (applyOthers) {
                if (actions.contains("disallow")) {
                    disallowed = true;
                    messages.add(actionMessage(filter, filter.getDisallowMessage(),
                            filter.getDisallowPage(), filter.getDescription()));
                    applied.add("disallow");
                }
                if (actions.contains("warn") || actions.contains("degrade")) {
                    warnings.add(actionMessage(filter, filter.getWarnMessage(),
                            filter.getWarnPage(), filter.getDescription()));
                    applied.add(actions.contains("warn") ? "warn" : "degrade");
                }
                if (actions.contains("tag")) {
                    String tag = tagOf(filter);
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                        applied.add("tag");
                    }
                }
                if (actions.contains("block")) {
                    blockFor(filter, viewer, ip);
                    applied.add("block");
                }
                if (actions.contains("blockautopromote")) {
                    blockAutopromote(viewer);
                    applied.add("blockautopromote");
                }
            } else {
                applied.add("throttle");
            }
            logHit(filter, viewer, ip, context, String.join(",", applied));
        }
        return new Outcome(disallowed, List.copyOf(messages), List.copyOf(warnings),
                String.join(",", tags), List.copyOf(matched));
    }

    @Override
    public List<TestHit> test(String pattern, int limit) {
        AbuseCondition condition = AbuseCondition.compile(pattern);
        int effective = Math.min(Math.max(1, limit), MAX_TEST_LIMIT);
        List<TestHit> hits = new ArrayList<>();
        for (AbuseFilterStore.RecentEdit edit : store.recentEdits(effective)) {
            Viewer author = viewerOf(edit.userId(), edit.userName());
            Map<String, Object> variables = AbuseVariables.edit(edit.pageId(), edit.pageTitle(),
                    false, edit.oldContent(), edit.content(), edit.summary(), false);
            AbuseContext context = baseContext(author, "");
            variables.forEach(context::put);
            boolean matched;
            try {
                matched = condition.test(context);
            } catch (RuntimeException exception) {
                matched = false;
            }
            hits.add(new TestHit(edit.revisionId(), edit.pageTitle(), edit.userName(),
                    edit.time(), edit.summary(), matched));
        }
        return hits;
    }

    // ==================== 日志 ====================

    @Override
    public List<WikiAbuseLog> logs(boolean includeHided, int limit) {
        return store.logs(includeHided, limit);
    }

    @Override
    public Optional<WikiAbuseLog> log(long id) {
        return store.findLog(id);
    }

    @Override
    public boolean hideLog(long id, boolean hided) {
        return store.hideLog(id, hided);
    }

    @Override
    public Map<String, Object> logVariables(WikiAbuseLog log) {
        String json = log == null ? "" : Strings.trimToEmpty(log.getVariables());
        if (json.isEmpty()) {
            return Map.of();
        }
        try {
            return AbuseJson.parse(json);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    // ==================== 操作 ====================

    private void blockFor(WikiAbuseFilter filter, Viewer viewer, String ip) {
        boolean loggedIn = viewer != null && viewer.loggedIn();
        String target = !loggedIn ? Strings.trimToEmpty(ip) : viewer.name();
        if (target.isEmpty()) {
            return;
        }
        int days = loggedIn
                ? (filter.getBlockUserDays() == null ? BLOCK_DAYS : filter.getBlockUserDays())
                : (filter.getBlockAnonDays() == null ? BLOCK_DAYS : filter.getBlockAnonDays());
        LocalDateTime expiresAt = days <= 0 ? null : LocalDateTime.now().plusDays(days);
        blockWriter.block(SYSTEM_ACCOUNT, target, expiresAt,
                message("abusefilter.blockReason", filter.getDescription()));
    }

    /** 警告 / 阻止文案：自定义文案 → 信息页正文 → 过滤器说明。 */
    private String actionMessage(WikiAbuseFilter filter, String message, String page, String fallback) {
        String text = Strings.trimToEmpty(message);
        if (!text.isEmpty()) {
            // 支持两种写法：系统消息键（如 abusefilter-warning）或直接写警告文案
            String resolved = support.messageOrNull(text);
            return applyMessageParams(resolved == null ? text : resolved, fallback);
        }
        String pageTitle = AbuseFilterSupport.messagePageTitle(page);
        if (!pageTitle.isEmpty() && pageStore != null) {
            try {
                String content = pageStore.byTitle(pageTitle)
                        .flatMap(snapshot -> pageStore.content(snapshot.id()))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .orElse(fallback);
                return applyMessageParams(content, fallback);
            } catch (RuntimeException ignored) {
                // 页面读取失败时使用说明，不影响拦截
            }
        }
        return applyMessageParams(fallback, fallback);
    }

    /** MediaWiki 消息参数：$1 = 过滤器说明；其它占位符留给消息本身。 */
    private static String applyMessageParams(String text, String description) {
        return text == null ? "" : text.replace("$1", Strings.trimToEmpty(description));
    }

    private void blockAutopromote(Viewer viewer) {
        if (viewer != null && viewer.loggedIn() && viewer.inGroup(AbuseFilterSupport.GROUP_AUTOCONFIRMED)) {
            userWriter.revokeGroup(viewer.id(), AbuseFilterSupport.GROUP_AUTOCONFIRMED);
        }
    }

    private void logHit(WikiAbuseFilter filter, Viewer viewer, String ip, AbuseContext context,
                        String actionsTaken) {
        WikiAbuseLog entry = new WikiAbuseLog();
        entry.setFilterId(filter.getId());
        entry.setUserId(viewer == null || !viewer.loggedIn() ? null : viewer.id());
        entry.setIp(Strings.trimToEmpty(ip));
        entry.setPageTitle(String.valueOf(context.get("page_title") == null
                ? "" : context.get("page_title")));
        entry.setAction(Strings.trimToEmpty((String) context.get("action")));
        entry.setActionsTaken(actionsTaken);
        entry.setVariables(AbuseJson.write(context.snapshot()));
        entry.setHided(false);
        entry.setCreateAt(LocalDateTime.now());
        store.insertLog(entry);
    }

    /** 节流判定：期间内已触发次数达到上限 → 执行其他操作；未达到 → 本次只记节流。 */
    private boolean throttleTripped(WikiAbuseFilter filter, AbuseContext context, Viewer viewer, String ip) {
        int count = filter.getThrottleCount() == null ? 0 : filter.getThrottleCount();
        int period = filter.getThrottlePeriod() == null ? 0 : filter.getThrottlePeriod();
        if (count < 1 || period < 1) {
            return true;
        }
        String key = filter.getId() + "|" + throttleGroupValue(filter.getThrottleGroup(), context, viewer, ip);
        ArrayDeque<Long> counter = throttleCounters.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long windowStart = now - period * 1000L;
        synchronized (counter) {
            while (!counter.isEmpty() && counter.peekFirst() < windowStart) {
                counter.pollFirst();
            }
            boolean tripped = counter.size() >= count;
            counter.addLast(now);
            return tripped;
        }
    }

    private static String throttleGroupValue(String group, AbuseContext context, Viewer viewer, String ip) {
        String value = Strings.trimToEmpty(group).toLowerCase(Locale.ROOT);
        String userName = viewer == null || !viewer.loggedIn() ? Strings.trimToEmpty(ip) : viewer.name();
        return switch (value) {
            case "ip" -> Strings.trimToEmpty(ip);
            case "page" -> String.valueOf(context.get("page_title"));
            case "user-ip", "ip-user" -> userName + "|" + Strings.trimToEmpty(ip);
            default -> userName;
        };
    }

    // ==================== 上下文 ====================

    private Viewer viewerOf(Long userId, String userName) {
        if (userId == null || userId <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = userStore == null ? Set.of() : userStore.groups(userId);
        return Viewer.of(userId, userName == null ? "" : userName, groups);
    }

    private AbuseContext baseContext(Viewer viewer, String ip) {
        boolean loggedIn = viewer != null && viewer.loggedIn();
        long userId = loggedIn ? viewer.id() : 0L;
        Set<String> groups = loggedIn ? viewer.groups() : Set.of();
        long editCount = 0;
        long age = 0;
        if (loggedIn) {
            AbuseFilterStore.UserStats stats = store.userStats(userId).orElse(null);
            if (stats != null) {
                editCount = stats.editCount();
                if (stats.registeredAt() != null) {
                    age = Math.max(0, Duration.between(stats.registeredAt(), LocalDateTime.now()).getSeconds());
                }
            }
        }
        boolean emailConfirmed = loggedIn && userStore != null
                && userStore.byId(userId).map(UserStore.UserRef::emailConfirmed).orElse(false);
        boolean blocked = loggedIn && accessCheck != null && accessCheck.blockNotice(viewer).isPresent();
        return new AbuseContext()
                .put("user_name", loggedIn ? viewer.name() : Strings.trimToEmpty(ip))
                .put("user_id", loggedIn ? userId : null)
                .put("user_groups", groupAliases(groups))
                .put("user_rights", groupAliases(groups))
                .put("user_type", loggedIn ? "named" : "ip")
                .put("user_editcount", editCount)
                .put("user_age", age)
                .put("user_emailconfirm", emailConfirmed)
                .put("user_blocked", blocked)
                .put("user_autoconfirmed", groups.contains(AbuseFilterSupport.GROUP_AUTOCONFIRMED))
                .put("ip", Strings.trimToEmpty(ip))
                .put("user_unnamed_ip", Strings.trimToEmpty(ip))
                .put("minor_edit", false)
                .put("timestamp", LocalDateTime.now().toString())
                .put("wiki_name", siteName())
                .put("wiki_language", "zh")
                .put("tor_exit_node", false);
    }

    /** 站点中文组名 → MediaWiki 内部组名别名（过滤器里的 sysop / autoconfirmed 可直接使用）。 */
    private static List<String> groupAliases(Set<String> groups) {
        LinkedHashSet<String> result = new LinkedHashSet<>(groups);
        for (String group : groups) {
            switch (group) {
                case "管理员" -> result.add("sysop");
                case "行政员" -> result.add("bureaucrat");
                case "自动确定用户" -> result.add("autoconfirmed");
                case "机器人" -> result.add("bot");
                case "用户" -> result.add("user");
                case "界面管理员" -> result.add("interface-admin");
                default -> {
                }
            }
        }
        return List.copyOf(result);
    }

    /** 站点显示名（仿 MediaWiki 的 {@code $wgSitename}；core 的站点设置键 {@code site.name}）。 */
    private String siteName() {
        return support.message("site.name", "WikiKernel");
    }

    private AbuseCondition conditionOf(String pattern) {
        String key = Strings.trimToEmpty(pattern);
        AbuseCondition cached = conditionCache.get(key);
        if (cached != null) {
            return cached;
        }
        AbuseCondition compiled = AbuseCondition.compile(key);
        conditionCache.put(key, compiled);
        return compiled;
    }

    // ==================== 工具 ====================

    private List<String> actionsOf(WikiAbuseFilter filter) {
        return splitCsv(filter.getActions());
    }

    private static List<String> splitCsv(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : text.split(",")) {
            String item = part.trim().toLowerCase(Locale.ROOT);
            if (!item.isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        return items;
    }

    private String normalizeActions(String actions) {
        List<String> items = splitCsv(actions);
        for (String item : items) {
            if (!ACTION_NAMES.contains(item)) {
                throw new IllegalArgumentException(message("abusefilter.unknownAction", item));
            }
        }
        return String.join(",", items);
    }

    private static String normalizeGroup(String group) {
        String value = Strings.trimToEmpty(group).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ip", "page", "user-ip", "ip-user" -> value;
            default -> "user";
        };
    }

    private String tagOf(WikiAbuseFilter filter) {
        String tags = Strings.trimToEmpty(filter.getTags());
        return tags.isEmpty() ? "abusefilter-" + filter.getId() : tags.replace(" ", "");
    }

    private static String trim(String value) {
        return Strings.trimToEmpty(value);
    }
}
