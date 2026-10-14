package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.log.LogEntryView;
import cc.thonly.wikikernel.log.LogFormatter;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code 特殊:最近更改} / {@code Special:RecentChanges}：仿 MediaWiki 的最近更改页。
 * <ul>
 *     <li>顶部：帮助按钮、页面说明、过滤器栏（激活的过滤器 / 过滤更改 / 命名空间 / 标签 / 动态更新 / 50次更改，7天）；</li>
 *     <li>按日期分组；同一词条同一天的多次编辑折叠为一行（{@code <details>}，显示“（N次更改）”）；</li>
 *     <li>每行：时间（差异 | 历史）页名（N次更改）（±字节）．．用户（留言 | 贡献）摘要；</li>
 *     <li>新页面标“新”；图例（缩略列表）解释 新 / 小 / 机 / (±123)；</li>
 *     <li>注册 / 权限 / 删除日志也混排进列表（来自通用日志系统）。</li>
 * </ul>
 * 参数：{@code ?limit=50|100|250|500&days=1|3|7|30&ns=前缀|main&tag=new}。
 */
@Component
public class RecentChangesSpecialPage implements SpecialPage {

    public static final String PARAM_LIMIT = "limit";
    public static final String PARAM_DAYS = "days";
    public static final String PARAM_NS = "ns";
    public static final String PARAM_TAG = "tag";
    /** 显示机器人编辑（默认隐藏；{@code ?hidebots=0} 显示，仿 MediaWiki 的「隐藏机器人」选项）。 */
    public static final String PARAM_HIDE_BOTS = "hidebots";

    private static final int DEFAULT_LIMIT = 50;
    private static final List<Integer> LIMIT_OPTIONS = List.of(50, 100, 250, 500);
    private static final int DEFAULT_DAYS = 7;
    private static final List<Integer> DAY_OPTIONS = List.of(1, 3, 7, 30);

    private static final String NS_MAIN = "main";
    private static final List<String> NAMESPACE_OPTIONS = List.of("模板:", "WikiKernel:", "帮助:", "用户讨论:");
    private static final String TAG_NEW = "new";

    /** 列表里的时间格式（HH:mm，Java 侧预格式化，模板直接输出）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 混排进最近更改的日志类型（创建/编辑已由版本代表，不重复）。 */
    private static final Set<String> LOG_TYPES = Set.of(
            LogTypeConfig.REGISTER, LogTypeConfig.RIGHTS, LogTypeConfig.DELETE,
            LogTypeConfig.MOVE, LogTypeConfig.PROTECT,
            LogTypeConfig.UPLOAD, LogTypeConfig.FILEDELETE,
            LogTypeConfig.BLOCK, LogTypeConfig.UNBLOCK, LogTypeConfig.IMPORT);

    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiPageMapper wikiPageMapper;
    private final UserService userService;
    private final LogService logService;
    private final LogFormatter logFormatter;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public RecentChangesSpecialPage(WikiRevisionMapper wikiRevisionMapper,
                                    WikiPageMapper wikiPageMapper,
                                    UserService userService,
                                    LogService logService,
                                    LogFormatter logFormatter,
                                    PermissionService permissionService,
                                    AccessControlService accessControlService,
                                    MessageSource messageSource) {
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.userService = userService;
        this.logService = logService;
        this.logFormatter = logFormatter;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "最近更改";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.recentChanges", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("RecentChanges", "Recentchanges", "最近修改");
    }

    @Override
    public String template() {
        return "special/recent-changes";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        WikiUser viewer = request.user();
        int defaultLimit = viewer != null && viewer.getRcLimit() != null && viewer.getRcLimit() > 0
                ? viewer.getRcLimit() : DEFAULT_LIMIT;
        int defaultDays = viewer != null && viewer.getRcDays() != null && viewer.getRcDays() > 0
                ? viewer.getRcDays() : DEFAULT_DAYS;
        int limit = parseChoice(parameters.get(PARAM_LIMIT), LIMIT_OPTIONS, defaultLimit);
        int days = parseChoice(parameters.get(PARAM_DAYS), DAY_OPTIONS, defaultDays);
        String ns = Strings.trimToEmpty(parameters.get(PARAM_NS));
        String tag = Strings.trimToEmpty(parameters.get(PARAM_TAG));
        // 机器人编辑默认隐藏（?hidebots=0 显示，仿 MediaWiki 的「隐藏机器人」选项）
        boolean hideBots = !"0".equals(Strings.trimToEmpty(parameters.get(PARAM_HIDE_BOTS)));

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        // 特殊页链接必须带前缀（name() 本身不带“特殊:”）
        String pagePath = WikiUrls.pagePath("特殊:" + name());

        // 下拉菜单与过滤器链接
        model.put("limitOptions", options(LIMIT_OPTIONS, value -> message("recentChanges.changeCount", value),
                value -> url(pagePath, value, days, ns, tag, hideBots)));
        model.put("dayOptions", options(DAY_OPTIONS, value -> message("recentChanges.lastDays", value),
                value -> url(pagePath, limit, value, ns, tag, hideBots)));
        model.put("namespaceOptions", namespaceOptions(pagePath, limit, days, ns, tag, hideBots));
        model.put("tagOptions", tagOptions(pagePath, limit, days, ns, tag, hideBots));
        // 「隐藏：机器人」选项（点击切换 hidebots）
        model.put("botOptions", List.of(option(message("recentChanges.botFilter"),
                url(pagePath, limit, days, ns, tag, !hideBots), hideBots)));
        model.put("selectedLimit", limit);
        model.put("selectedDays", days);
        model.put("selectedNs", ns);
        model.put("selectedTag", tag);
        model.put("selectedNamespaceLabel", NS_MAIN.equals(ns) ? message("recentChanges.namespaceMain")
                : ns);
        model.put("limitDaysLabel", message("recentChanges.limitDays", limit, days));
        // 已应用的过滤器（仿 MediaWiki RCFilters）：没有过滤器时显示空状态文案
        List<Map<String, Object>> activeFilters = new ArrayList<>();
        if (hideBots) {
            activeFilters.add(filter(message("recentChanges.human"),
                    url(pagePath, limit, days, ns, tag, false)));
        }
        if (!ns.isEmpty()) {
            activeFilters.add(filter(NS_MAIN.equals(ns) ? message("recentChanges.namespaceMain") : ns,
                    url(pagePath, limit, days, "", tag, hideBots)));
        }
        if (!tag.isEmpty()) {
            activeFilters.add(filter(message("recentChanges.tagNew"),
                    url(pagePath, limit, days, ns, "", hideBots)));
        }
        model.put("activeFilters", activeFilters);
        // 清除所有过滤器 = 全部命名空间 + 无标签 + 显示机器人（此后即为「所有贡献均已显示」）
        model.put("clearUrl", url(pagePath, limit, days, "", "", false));

        // 版本（编辑/创建）；hideBots 时在查询里排除机器人编辑（bot 为 null 的旧数据按非机器人处理）
        LambdaQueryWrapper<WikiRevision> revisionQuery = Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false)
                .ge(WikiRevision::getCreateAt, since)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT " + limit);
        if (hideBots) {
            revisionQuery.and(wrapper -> wrapper.eq(WikiRevision::getBot, false)
                    .or().isNull(WikiRevision::getBot));
        }
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(revisionQuery);

        Map<Long, WikiPage> pageById = loadVisiblePages(revisions, ns);
        // 按访问规则过滤：无权查看的页面不出现在最近更改里（标题不泄露）
        revisions = accessControlService.filterReadable(revisions,
                revision -> {
                    WikiPage page = pageById.get(revision.getPageId());
                    return page == null ? null : page.getTitle();
                },
                request.user());
        Map<Long, String> userNames = loadUserNames(revisions);
        Map<Long, List<WikiRevision>> revisionsByPage = groupRevisions(revisions, pageById);

        // 组装（日期, 页面）分组 + 字节变化
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<Long, List<WikiRevision>> entry : revisionsByPage.entrySet()) {
            WikiPage page = pageById.get(entry.getKey());
            List<WikiRevision> pageRevisions = entry.getValue();
            List<Map<String, Object>> changes = new ArrayList<>();
            for (int i = 0; i < pageRevisions.size(); i++) {
                WikiRevision revision = pageRevisions.get(i);
                Map<String, Object> change = change(page, revision, userNames);
                boolean newPage = Boolean.TRUE.equals(change.get("newPage"));
                if (TAG_NEW.equals(tag) && !newPage) {
                    continue;
                }
                WikiRevision previous = i + 1 < pageRevisions.size()
                        ? pageRevisions.get(i + 1)
                        : previousRevision(page.getId(), revision.getId());
                applyBytes(change, revision, previous, newPage);
                changes.add(change);
            }
            if (changes.isEmpty()) {
                continue;
            }
            // 摘要行是 latest 的副本，额外带“（N次更改）”；展开行不带
            Map<String, Object> latest = new LinkedHashMap<>(changes.get(0));
            latest.put("count", changes.size());
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("kind", "group");
            group.put("time", latest.get("time"));
            group.put("count", changes.size());
            group.put("latest", latest);
            group.put("changes", changes);
            // 动态更新用的稳定键（按天 + 页面；客户端用来判断哪些是新条目）
            group.put("key", "g-" + ((LocalDateTime) latest.get("time")).toLocalDate() + "-" + page.getId());
            groups.add(group);
        }

        // 日志（注册/权限/删除）；机器人用户的日志操作与机器人编辑一样默认隐藏
        boolean canManage = request.user() != null && isManager(request);
        Set<Long> botUserIds = botUserIds();
        List<Map<String, Object>> logEntries = logEntries(since, ns, limit, canManage, request.user(),
                botUserIds, hideBots);

        // 按日期分组
        Map<LocalDate, List<Map<String, Object>>> byDay = new HashMap<>();
        for (Map<String, Object> group : groups) {
            LocalDate day = ((LocalDateTime) group.get("time")).toLocalDate();
            byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(group);
        }
        for (Map<String, Object> logEntry : groupLogs(logEntries)) {
            LocalDate day = ((LocalDateTime) logEntry.get("time")).toLocalDate();
            byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(logEntry);
        }

        DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern(
                message("recentChanges.dateFormat"), LocaleContextHolder.getLocale());
        List<Map<String, Object>> dayList = byDay.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, List<Map<String, Object>>>comparingByKey().reversed())
                .map(dayEntry -> {
                    List<Map<String, Object>> entries = new ArrayList<>(dayEntry.getValue());
                    entries.sort(Comparator.comparing(
                            entry -> (LocalDateTime) entry.get("time"), Comparator.reverseOrder()));
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("label", dayFormat.format(dayEntry.getKey()));
                    day.put("entries", entries);
                    return day;
                })
                .toList();
        model.put("days", dayList);
    }

    // ==================== 数据组装 ====================

    private Map<Long, WikiPage> loadVisiblePages(List<WikiRevision> revisions, String ns) {
        if (revisions.isEmpty()) {
            return Map.of();
        }
        List<Long> pageIds = revisions.stream().map(WikiRevision::getPageId).distinct().toList();
        Map<Long, WikiPage> pageById = new LinkedHashMap<>();
        for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getId, pageIds)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false))) {
            if (!matchesNamespace(page.getTitle(), ns)) {
                continue;
            }
            pageById.put(page.getId(), page);
        }
        return pageById;
    }

    /** 命名空间过滤：空=全部；main=主命名空间（无冒号）；否则按前缀。 */
    private boolean matchesNamespace(String title, String ns) {
        if (ns.isEmpty()) {
            return true;
        }
        if (NS_MAIN.equals(ns)) {
            return title.indexOf(':') < 0;
        }
        return title.startsWith(ns);
    }

    private Map<Long, String> loadUserNames(List<WikiRevision> revisions) {
        Set<Long> userIds = new LinkedHashSet<>();
        revisions.stream().map(WikiRevision::getUserId).filter(Objects::nonNull).forEach(userIds::add);
        return userService.usernames(userIds);
    }

    private Map<Long, List<WikiRevision>> groupRevisions(List<WikiRevision> revisions, Map<Long, WikiPage> pageById) {
        Map<Long, List<WikiRevision>> byPage = new LinkedHashMap<>();
        for (WikiRevision revision : revisions) {
            if (pageById.containsKey(revision.getPageId())) {
                byPage.computeIfAbsent(revision.getPageId(), key -> new ArrayList<>()).add(revision);
            }
        }
        return byPage;
    }

    private Map<String, Object> change(WikiPage page, WikiRevision revision, Map<Long, String> userNames) {
        String pageUrl = WikiUrls.pagePath(page.getTitle());
        String userName = revision.getUserId() == null ? null : userNames.get(revision.getUserId());

        Map<String, Object> change = new LinkedHashMap<>();
        // 时间统一换算到用户时区：分组（按天）、动态更新键与显示都用换算后的值
        LocalDateTime userTime = WikiTime.convert(revision.getCreateAt());
        change.put("time", userTime);
        change.put("timeText", TIME_FORMAT.format(userTime));
        change.put("pageTitle", page.getTitle());
        change.put("pageUrl", pageUrl);
        change.put("historyUrl", pageUrl + "?action=history");
        change.put("oldUrl", pageUrl + "?oldid=" + revision.getId());
        // 差异链接（仿 MediaWiki 最近更改：与该次编辑的上一版本对比）
        change.put("diffUrl", pageUrl + "?diff=prev&oldid=" + revision.getId());
        change.put("userName", userName);
        change.put("userUrl", userName == null ? null : userUrl(userName));
        change.put("talkUrl", userName == null ? null : WikiUrls.pagePath("用户讨论:" + userName) + "?action=edit");
        change.put("contributionsUrl", userName == null ? null
                : WikiUrls.pagePath(UserToolsSidebar.CONTRIBUTIONS_PAGE) + "?name=" + encode(userName));
        change.put("commentText", Strings.isBlank(revision.getComment()) ? null : revision.getComment());
        change.put("sectionOnly", isSectionOnly(revision.getComment()));
        change.put("newPage", page.getCreateAt() != null && page.getCreateAt().equals(revision.getCreateAt()));
        // 机器人编辑与变更标签（滥用过滤器打标签 / 机器人编辑用）
        change.put("bot", Boolean.TRUE.equals(revision.getBot()));
        change.put("minor", Boolean.TRUE.equals(revision.getMinor()));
        change.put("tags", parseTags(revision.getTags()));
        change.put("count", null);
        return change;
    }

    /** 变更标签（逗号分隔 → 列表）。 */
    private static List<String> parseTags(String tags) {
        String value = Strings.trimToEmpty(tags);
        if (value.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    /** 字节变化：新页面 = 内容字节数；否则 = 本版本与前一版本的字节差（前一版本不可见时不显示）。 */
    private void applyBytes(Map<String, Object> change, WikiRevision revision,
                            WikiRevision previous, boolean newPage) {
        Integer bytes;
        if (newPage) {
            bytes = size(revision.getContent());
        } else {
            bytes = previous == null ? null : size(revision.getContent()) - size(previous.getContent());
        }
        change.put("bytesText", bytes == null ? null
                : bytes == 0 ? "(0)"
                : "(" + (bytes > 0 ? "+" : "−") + Math.abs(bytes) + ")");
        change.put("bytesClass", bytes == null ? null
                : bytes > 0 ? "rc-bytes-plus" : bytes < 0 ? "rc-bytes-minus" : "rc-bytes-zero");
    }

    private WikiRevision previousRevision(Long pageId, Long revisionId) {
        return wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .eq(WikiRevision::getDeleted, false)
                .lt(WikiRevision::getId, revisionId)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT 1"));
    }

    private int size(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 编辑摘要是否只是段落注释（{@code /* 段落 *} /）——只显示原文，不加括号（仿 MediaWiki）。 */
    private static boolean isSectionOnly(String comment) {
        return Strings.trimToEmpty(comment).matches("^/\\*\\s*.+?\\s*\\*/\\s*$");
    }

    /**
     * 连续的同类型日志合并成一组（仿 MediaWiki 的「（论坛日志） [甲；乙]」：
     * 摘要行显示日志名 + 涉及的用户 / 目标，展开后是各条日志），跨日期不合并。
     */
    private List<Map<String, Object>> groupLogs(List<Map<String, Object>> logs) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        String pendingType = null;
        LocalDate pendingDay = null;
        for (Map<String, Object> log : logs) {
            String type = String.valueOf(log.get("logType"));
            LocalDate day = ((LocalDateTime) log.get("time")).toLocalDate();
            if (pending.isEmpty() || (type.equals(pendingType) && day.equals(pendingDay))) {
                pending.add(log);
                pendingType = type;
                pendingDay = day;
                continue;
            }
            flushLogGroup(result, pending);
            pending = new ArrayList<>();
            pending.add(log);
            pendingType = type;
            pendingDay = day;
        }
        flushLogGroup(result, pending);
        return result;
    }

    private void flushLogGroup(List<Map<String, Object>> result, List<Map<String, Object>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() == 1) {
            result.add(pending.get(0));
            return;
        }
        Map<String, Object> first = pending.get(0);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("kind", "loggroup");
        group.put("time", first.get("time"));
        group.put("timeText", first.get("timeText"));
        group.put("label", first.get("label"));
        group.put("labelUrl", first.get("labelUrl"));
        group.put("entries", List.copyOf(pending));
        // 动态更新用的稳定键（按天 + 类型：同一天的同类日志都在这一组里原地更新）
        group.put("key", "lg-" + ((LocalDateTime) first.get("time")).toLocalDate() + "-" + first.get("logType"));
        // 涉及的目标（去重、保序，最多 8 个）：[甲；乙]
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Map<String, Object> log : pending) {
            Object target = log.get("targetText");
            if (target != null && !target.toString().isEmpty()) {
                targets.add(target.toString());
            }
            if (targets.size() >= 8) {
                break;
            }
        }
        group.put("targetsText", targets.isEmpty() ? null
                : "[" + String.join(message("recentChanges.logGroupSeparator"), targets) + "]");
        result.add(group);
    }

    private List<Map<String, Object>> logEntries(LocalDateTime since, String ns, int limit,
                                                 boolean canManage, WikiUser viewer,
                                                 Set<Long> botUserIds, boolean hideBots) {
        List<WikiLog> logs = logService.recent(null, null, canManage, limit).stream()
                .filter(log -> LOG_TYPES.contains(log.getLogType()))
                .filter(log -> logVisible(log, viewer))
                .filter(log -> log.getTime() != null && !log.getTime().isBefore(since))
                // 机器人用户的日志操作：默认隐藏（?hidebots=0 显示并标「机」）
                .filter(log -> !hideBots || log.getActorUserId() == null
                        || !botUserIds.contains(log.getActorUserId()))
                .filter(log -> ns.isEmpty() || (!LogTypeConfig.REGISTER.equals(log.getLogType())
                        && !LogTypeConfig.RIGHTS.equals(log.getLogType())
                        && !LogTypeConfig.BLOCK.equals(log.getLogType())
                        && !LogTypeConfig.UNBLOCK.equals(log.getLogType())))
                .toList();
        if (logs.isEmpty()) {
            return List.of();
        }
        List<LogEntryView> views = logFormatter.format(logs);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LogEntryView view : views) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", "log");
            entry.put("bot", view.getLog().getActorUserId() != null
                    && botUserIds.contains(view.getLog().getActorUserId()));
            LocalDateTime userTime = WikiTime.convert(view.getLog().getTime());
            entry.put("time", userTime);
            entry.put("timeText", TIME_FORMAT.format(userTime));
            entry.put("logType", view.getLog().getLogType());
            entry.put("logId", view.getLog().getId());
            // 与折叠组共用「按天 + 类型」的键：单条日志之后变成组时原地更新，不会重复
            entry.put("key", "lg-" + userTime.toLocalDate() + "-" + view.getLog().getLogType());
            entry.put("hided", Boolean.TRUE.equals(view.getLog().getHided()));
            entry.put("label", message("recentChanges.logLabel",
                    message("recentChanges.logName." + view.getLog().getLogType(),
                            message("log.typeName." + view.getLog().getLogType()))));
            entry.put("actionText", view.getActionText());
            entry.put("actionBefore", view.getActionBefore());
            entry.put("actionAfter", view.getActionAfter());
            entry.put("targetText", view.getTargetText());
            entry.put("targetUrl", view.getTargetUrl());
            // 日志名链到「特殊:日志?type=…」（仿 MediaWiki：点日志名看该类型全部日志）
            entry.put("labelUrl", WikiUrls.pagePath("特殊:日志") + "?type=" + view.getLog().getLogType());
            entry.put("detailText", view.getDetailText());
            entry.put("actorName", view.getActorName());
            entries.add(entry);
        }
        return entries;
    }

    private boolean isManager(SpecialPageRequest request) {
        return request.user() != null && permissionService.canManage(request.user().getId());
    }

    /** 注册 / 权限 / 封禁日志与页面无关；文件日志按 文件:<name> 判定；其余（删除 / 移动 / 保护）要求目标页可读。 */
    private boolean logVisible(WikiLog log, WikiUser viewer) {
        if (LogTypeConfig.REGISTER.equals(log.getLogType())
                || LogTypeConfig.RIGHTS.equals(log.getLogType())
                || LogTypeConfig.BLOCK.equals(log.getLogType())
                || LogTypeConfig.UNBLOCK.equals(log.getLogType())) {
            return true;
        }
        if (LogTypeConfig.UPLOAD.equals(log.getLogType()) || LogTypeConfig.FILEDELETE.equals(log.getLogType())) {
            return accessControlService.canRead("文件:" + log.getTarget(), viewer);
        }
        return accessControlService.canRead(log.getTarget(), viewer);
    }

    // ==================== 过滤链接 ====================

    private List<Map<String, Object>> namespaceOptions(String pagePath, int limit, int days, String ns, String tag,
                                                       boolean hideBots) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option(message("recentChanges.namespaceAll"),
                url(pagePath, limit, days, "", tag, hideBots), ns.isEmpty()));
        options.add(option(message("recentChanges.namespaceMain"),
                url(pagePath, limit, days, NS_MAIN, tag, hideBots), NS_MAIN.equals(ns)));
        for (String prefix : NAMESPACE_OPTIONS) {
            options.add(option(prefix, url(pagePath, limit, days, prefix, tag, hideBots), prefix.equals(ns)));
        }
        return options;
    }

    private List<Map<String, Object>> tagOptions(String pagePath, int limit, int days, String ns, String tag,
                                                 boolean hideBots) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option(message("recentChanges.tagAll"),
                url(pagePath, limit, days, ns, "", hideBots), tag.isEmpty()));
        options.add(option(message("recentChanges.tagNew"),
                url(pagePath, limit, days, ns, TAG_NEW, hideBots), TAG_NEW.equals(tag)));
        return options;
    }

    private List<Map<String, Object>> options(List<Integer> values,
                                              java.util.function.Function<Integer, String> label,
                                              java.util.function.Function<Integer, String> url) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Integer value : values) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", label.apply(value));
            option.put("url", url.apply(value));
            options.add(option);
        }
        return options;
    }

    private Map<String, Object> option(String label, String url, boolean selected) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("label", label);
        option.put("url", url);
        option.put("selected", selected);
        return option;
    }

    /** 一个已应用的过滤器（仿 RCFilters 的过滤器标签）：文案 + 移除链接。 */
    private static Map<String, Object> filter(String label, String removeUrl) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("label", label);
        filter.put("removeUrl", removeUrl);
        return filter;
    }

    private String url(String pagePath, int limit, int days, String ns, String tag, boolean hideBots) {
        StringBuilder url = new StringBuilder(pagePath)
                .append("?limit=").append(limit)
                .append("&days=").append(days);
        if (!ns.isEmpty()) {
            url.append("&ns=").append(encode(ns));
        }
        if (!tag.isEmpty()) {
            url.append("&tag=").append(encode(tag));
        }
        if (!hideBots) {
            url.append("&hidebots=0");
        }
        return url.toString();
    }

    /** 「机器人」组用户的 ID 集合（日志过滤 / 标记用）。 */
    private Set<Long> botUserIds() {
        Set<Long> ids = new LinkedHashSet<>();
        permissionService.allUserPermissions().forEach((userId, groups) -> {
            if (groups.contains(PermissionService.GROUP_BOT)) {
                ids.add(userId);
            }
        });
        return ids;
    }

    private String userUrl(String userName) {
        return WikiUrls.pagePath("用户:" + userName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int parseChoice(String raw, List<Integer> allowed, int fallback) {
        try {
            int value = Integer.parseInt(Strings.trimToEmpty(raw));
            return allowed.contains(value) ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
