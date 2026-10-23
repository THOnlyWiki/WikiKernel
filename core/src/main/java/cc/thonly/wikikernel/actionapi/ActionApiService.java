package cc.thonly.wikikernel.actionapi;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.ActionApiException;
import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.ActionApiRequest;
import cc.thonly.wikikernel.common.extension.ApiPageRef;
import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.http.ClientIpResolver;
import cc.thonly.wikikernel.http.RequestMetadata;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.search.SearchService;
import cc.thonly.wikikernel.special.BlockSpecialPage;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.ActionHookService;
import cc.thonly.wikikernel.service.BlockExpiry;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.EditHookService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.RateLimiterService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.transfer.TransferTimes;
import cc.thonly.wikikernel.transfer.WikiSha1;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.RequestActionContext;
import cc.thonly.wikikernel.web.RequestEditContext;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.wikitext.LineDiff;
import cc.thonly.wikikernel.wikitext.SignatureExpander;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import cc.thonly.wikikernel.wikitext.WikiSections;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MediaWiki Action API 兼容层（{@code /api.php}）：query / parse / edit / login / logout / tokens / opensearch。
 * <p>
 * 覆盖机器人最常用的子集：token 流程、页面内容读写、列表与最近更改、搜索、分类成员。
 * 未实现的参数会被忽略；不支持的动作返回 MediaWiki 风格的错误对象（HTTP 200 + {@code error.code}）。
 */
@Service
public class ActionApiService {

    /** Action API 错误（code + info，由控制器转成 MediaWiki 风格 JSON）。 */
    public static class ApiError extends RuntimeException {
        private final String code;
        private final Map<String, Object> extra;

        public ApiError(String code, String info) {
            this(code, info, Map.of());
        }

        public ApiError(String code, String info, Map<String, Object> extra) {
            super(info);
            this.code = code;
            this.extra = extra;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> extra() {
            return extra;
        }
    }

    /** 列表结果：条目 + 续传参数（可空）。 */
    private record ListResult(List<Map<String, Object>> items, Map<String, Object> next) {
    }

    private final WikiPageService wikiPageService;
    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final BlockService blockService;
    private final TitleBlacklistService titleBlacklistService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterService rateLimiterService;
    private final SearchService searchService;
    private final PageCategoryService pageCategoryService;
    private final LogService logService;
    private final FileService fileService;
    private final WikitextRenderer wikitextRenderer;
    private final MessageSource messageSource;
    private final SiteNameService siteNameService;
    private final PluginManager pluginManager;
    private final PagePropertyStore pagePropertyStore;
    /** 编辑钩子调度：{@code api.php?action=edit} 也走插件的保存前钩子（source = api）。 */
    private final EditHookService editHookService;
    /** 动作前钩子调度：{@code api.php?action=upload} 走插件的通用动作钩子（source = api）。 */
    private final ActionHookService actionHookService;

    public ActionApiService(WikiPageService wikiPageService,
                            WikiPageMapper wikiPageMapper,
                            WikiRevisionMapper wikiRevisionMapper,
                            UserService userService,
                            PermissionService permissionService,
                            AccessControlService accessControlService,
                            BlockService blockService,
                            TitleBlacklistService titleBlacklistService,
                            ClientIpResolver clientIpResolver,
                            RateLimiterService rateLimiterService,
                            SearchService searchService,
                            PageCategoryService pageCategoryService,
                            LogService logService,
                            FileService fileService,
                            WikitextRenderer wikitextRenderer,
                            MessageSource messageSource,
                            SiteNameService siteNameService,
                            PluginManager pluginManager,
                            PagePropertyStore pagePropertyStore,
                            EditHookService editHookService,
                            ActionHookService actionHookService) {
        this.wikiPageService = wikiPageService;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.blockService = blockService;
        this.titleBlacklistService = titleBlacklistService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiterService = rateLimiterService;
        this.searchService = searchService;
        this.pageCategoryService = pageCategoryService;
        this.logService = logService;
        this.fileService = fileService;
        this.wikitextRenderer = wikitextRenderer;
        this.messageSource = messageSource;
        this.siteNameService = siteNameService;
        this.pluginManager = pluginManager;
        this.pagePropertyStore = pagePropertyStore;
        this.editHookService = editHookService;
        this.actionHookService = actionHookService;
    }

    // ==================== 入口 ====================

    public Object handle(String action, Map<String, String> params, HttpServletRequest request, int formatVersion) {
        return switch (action) {
            case "query" -> query(params, request, formatVersion);
            case "parse" -> parse(params, request, formatVersion);
            case "edit" -> edit(params, request, formatVersion);
            case "move" -> move(params, request, formatVersion);
            case "delete" -> delete(params, request, formatVersion);
            case "protect" -> protect(params, request, formatVersion);
            case "block" -> block(params, request, formatVersion);
            case "unblock" -> unblock(params, request, formatVersion);
            case "upload" -> upload(params, request, formatVersion);
            case "compare" -> compare(params, request, formatVersion);
            case "rollback" -> rollback(params, request, formatVersion);
            case "login" -> login(params, request, formatVersion);
            case "logout" -> logout(request);
            case "tokens" -> legacyTokens(params, request);
            case "opensearch" -> opensearch(params, request);
            case "help" -> help();
            default -> pluginAction(action, params, request, formatVersion);
        };
    }

    /** 插件提供的 action（common 的 {@code ActionApiModule}）；未命中返回 {@code unknown_action}。 */
    private Object pluginAction(String action, Map<String, String> params,
                                HttpServletRequest request, int formatVersion) {
        for (ActionApiModule module : pluginManager.extensions(ActionApiModule.class)) {
            if (!action.equalsIgnoreCase(module.action())) {
                continue;
            }
            try {
                return module.handle(new PluginActionRequest(params, formatVersion, UserSession.current(request)));
            } catch (ActionApiException e) {
                throw new ApiError(e.code(), e.info());
            }
        }
        throw new ApiError("unknown_action",
                "Unrecognized value for parameter \"action\": " + action);
    }

    /** {@link ActionApiRequest} 的 core 实现：页面 / 属性查询走现有服务。 */
    private final class PluginActionRequest implements ActionApiRequest {

        private final Map<String, String> params;
        private final int formatVersion;
        private final WikiUser user;

        private PluginActionRequest(Map<String, String> params, int formatVersion, WikiUser user) {
            this.params = Map.copyOf(params);
            this.formatVersion = formatVersion;
            this.user = user;
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        public int formatVersion() {
            return formatVersion;
        }

        @Override
        public String username() {
            return user == null ? "" : user.getUsername();
        }

        @Override
        public boolean loggedIn() {
            return user != null;
        }

        @Override
        public Set<String> groups() {
            return user == null || user.getId() == null
                    ? Set.of()
                    : permissionService.namesOf(user.getId());
        }

        @Override
        public List<ApiPageRef> resolvePages(String titles) {
            List<ApiPageRef> result = new ArrayList<>();
            if (titles == null || titles.isBlank()) {
                return result;
            }
            for (String title : titles.split("\\|")) {
                String normalized = WikiTitles.normalize(title.trim());
                if (normalized.isEmpty()) {
                    result.add(new ApiPageRef(0, title.trim(), true, null));
                    continue;
                }
                WikiPage page = wikiPageService.getVisibleByTitle(normalized).orElse(null);
                if (page == null) {
                    result.add(new ApiPageRef(0, normalized, true, null));
                    continue;
                }
                String content = wikiPageService.getLatestContent(page.getId()).orElse("");
                String redirect = WikiRedirect.targetOf(content, page.getTitle()).orElse(null);
                result.add(new ApiPageRef(page.getId(), page.getTitle(), false, redirect));
            }
            return result;
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds, Collection<String> names) {
            return pagePropertyStore.batch(pageIds, names);
        }

        @Override
        public Map<Long, Map<String, String>> properties(Collection<Long> pageIds) {
            return pagePropertyStore.all(pageIds);
        }

        @Override
        public Optional<String> content(long pageId) {
            return wikiPageService.getLatestContent(pageId);
        }
    }

    // ==================== query ====================

    private Map<String, Object> query(Map<String, String> params, HttpServletRequest request, int fv) {
        boolean v2 = fv == 2;
        WikiUser user = UserSession.current(request);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("batchcomplete", v2 ? Boolean.TRUE : "");
        Map<String, Object> query = new LinkedHashMap<>();

        // meta
        for (String meta : splitComma(params.get("meta"))) {
            switch (meta) {
                case "siteinfo" -> {
                    query.put("general", siteinfo(request));
                    query.put("namespaces", namespaces(v2));
                }
                case "userinfo" -> query.put("userinfo", userinfo(user, request));
                case "tokens" -> query.put("tokens", tokens(request, params.get("type")));
                default -> throw new ApiError("badvalue",
                        "Unrecognized value for parameter \"meta\": " + meta);
            }
        }

        // pages（titles / pageids / revids）；redirects=1 时跟随 #REDIRECT
        List<Map<String, Object>> redirectRows = new ArrayList<>();
        List<PageRef> refs = resolvePages(params, user,
                "1".equals(Strings.trimToEmpty(params.get("redirects"))), redirectRows);
        if (!redirectRows.isEmpty()) {
            query.put("redirects", redirectRows);
        }
        Set<Long> revIdFilter = parseIdSet(params.get("revids"));
        List<String> props = splitComma(params.get("prop"));

        Map<String, Object> continueMap = new LinkedHashMap<>();
        if (!refs.isEmpty()) {
            if (props.isEmpty()) {
                props = List.of();
            }
            List<Map<String, Object>> pageMaps = new ArrayList<>();
            for (PageRef ref : refs) {
                pageMaps.add(pageMap(ref, props, revIdFilter, params, user, v2, continueMap));
            }
            putPages(query, refs, pageMaps, v2, params);
        }

        // list
        for (String list : splitComma(params.get("list"))) {
            ListResult result = switch (list) {
                case "allpages" -> allpages(params, user, v2);
                case "search" -> searchList(params, user, request);
                case "recentchanges" -> recentChanges(params, user, v2);
                case "users" -> users(params, v2);
                case "usercontribs" -> userContribs(params, user, v2);
                case "categorymembers" -> categoryMembers(params, user, v2);
                case "allcategories" -> allCategories(params, v2);
                case "logevents" -> logEvents(params, user, v2);
                case "allimages" -> allImages(params, v2, request);
                default -> throw new ApiError("badvalue",
                        "Unrecognized value for parameter \"list\": " + list);
            };
            query.put(list, result.items());
            if (result.next() != null) {
                continueMap.putAll(result.next());
            }
        }

        if (!query.isEmpty()) {
            root.put("query", query);
        }
        if (!continueMap.isEmpty()) {
            continueMap.put("continue", "-||");
            root.put("continue", continueMap);
        }
        return root;
    }

    /** 页面引用（存在 / 缺失 + 解析到的页面）。 */
    private record PageRef(WikiPage page, String title, int ns, boolean missing) {
    }

    private List<PageRef> resolvePages(Map<String, String> params, WikiUser user) {
        return resolvePages(params, user, false, new ArrayList<>());
    }

    private List<PageRef> resolvePages(Map<String, String> params, WikiUser user, boolean followRedirects,
                                       List<Map<String, Object>> redirectRows) {
        List<PageRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : splitPipe(params.get("titles"))) {
            addTitleRef(raw, user, followRedirects, redirectRows, seen, refs);
        }
        for (String raw : splitPipe(params.get("pageids"))) {
            Long id = parseLong(raw);
            if (id == null) {
                continue;
            }
            WikiPage page = wikiPageMapper.selectById(id);
            if (page == null || Boolean.TRUE.equals(page.getDeleted())
                    || Boolean.TRUE.equals(page.getHided())) {
                continue;
            }
            if (!seen.add(page.getTitle())) {
                continue;
            }
            refs.add(followRedirects ? followRedirect(page, user, redirectRows) : pageRefOf(page.getTitle(), user));
        }
        for (String raw : splitPipe(params.get("revids"))) {
            Long revId = parseLong(raw);
            if (revId == null) {
                continue;
            }
            WikiRevision revision = wikiRevisionMapper.selectById(revId);
            if (revision == null || Boolean.TRUE.equals(revision.getDeleted())) {
                continue;
            }
            WikiPage page = wikiPageMapper.selectById(revision.getPageId());
            if (page == null || Boolean.TRUE.equals(page.getDeleted())
                    || Boolean.TRUE.equals(page.getHided())) {
                continue;
            }
            if (!seen.add(page.getTitle())) {
                continue;
            }
            refs.add(followRedirects ? followRedirect(page, user, redirectRows) : pageRefOf(page.getTitle(), user));
        }
        return refs;
    }

    private void addTitleRef(String raw, WikiUser user, boolean followRedirects,
                             List<Map<String, Object>> redirectRows, Set<String> seen, List<PageRef> refs) {
        String title = normalizeTitle(raw);
        if (title.isEmpty() || !seen.add(title)) {
            return;
        }
        PageRef ref = pageRefOf(title, user);
        refs.add(followRedirects && ref.page() != null ? followRedirect(ref.page(), user, redirectRows) : ref);
    }

    /** 跟随 #REDIRECT（最多 5 跳），并把 from → to 记进 {@code query.redirects}。 */
    private PageRef followRedirect(WikiPage page, WikiUser user, List<Map<String, Object>> redirectRows) {
        WikiPage current = page;
        for (int hop = 0; hop < 5; hop++) {
            String content = wikiPageService.getLatestContent(current.getId()).orElse("");
            String target = WikiRedirect.targetOf(content, current.getTitle())
                    .map(WikiTitles::normalize).orElse(null);
            if (target == null) {
                return pageRefOf(current.getTitle(), user);
            }
            PageRef targetRef = pageRefOf(target, user);
            redirectRows.add(Map.of("from", current.getTitle(), "to", targetRef.title()));
            if (targetRef.page() == null) {
                return targetRef;
            }
            current = targetRef.page();
        }
        return pageRefOf(current.getTitle(), user);
    }

    private PageRef pageRefOf(String title, WikiUser user) {
        WikiPage page = wikiPageService.getVisibleByTitle(title).orElse(null);
        if (page == null || !accessControlService.canRead(title, user)) {
            return new PageRef(null, title, ActionApiNamespaces.idOf(title), true);
        }
        return new PageRef(page, page.getTitle(), ActionApiNamespaces.idOf(page.getTitle()), false);
    }

    private void putPages(Map<String, Object> query, List<PageRef> refs,
                          List<Map<String, Object>> pageMaps, boolean v2, Map<String, String> params) {
        if (v2) {
            query.put("pages", pageMaps);
        } else {
            Map<String, Object> pages = new LinkedHashMap<>();
            int missingIndex = -1;
            for (int i = 0; i < refs.size(); i++) {
                PageRef ref = refs.get(i);
                String key = ref.page() != null ? String.valueOf(ref.page().getId()) : String.valueOf(missingIndex--);
                pages.put(key, pageMaps.get(i));
            }
            query.put("pages", pages);
        }
        if ("1".equals(Strings.trimToEmpty(params.get("indexpageids")))) {
            List<Object> ids = new ArrayList<>();
            for (PageRef ref : refs) {
                if (ref.page() != null) {
                    ids.add(v2 ? ref.page().getId() : String.valueOf(ref.page().getId()));
                }
            }
            query.put("indexpageids", ids);
        }
    }

    private Map<String, Object> pageMap(PageRef ref, List<String> props, Set<Long> revIdFilter,
                                        Map<String, String> params, WikiUser user, boolean v2,
                                        Map<String, Object> continueMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ns", ref.ns());
        map.put("title", ref.title());
        if (ref.missing()) {
            map.put("missing", v2 ? Boolean.TRUE : "");
            return map;
        }
        WikiPage page = ref.page();
        map.put("pageid", page.getId());
        for (String prop : props) {
            switch (prop) {
                case "info" -> infoProp(map, page, user, v2);
                case "revisions" -> revisionsProp(map, page, revIdFilter, params, user, v2, continueMap);
                case "categories" -> categoriesProp(map, page, v2);
                case "imageinfo" -> imageInfoProp(map, page, params, v2);
                default -> {
                    // 忽略未知 prop（与 MediaWiki 的 warnings 行为对齐，不中断请求）
                }
            }
        }
        return map;
    }

    private void infoProp(Map<String, Object> map, WikiPage page, WikiUser user, boolean v2) {
        map.put("contentmodel", "wikitext");
        map.put("pagelanguage", locale().getLanguage());
        map.put("touched", timestamp(page.getUpdateAt()));
        map.put("lastrevid", page.getLatestRevisionId());
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        map.put("length", WikiSha1.bytes(content));
        boolean redirect = WikiRedirect.targetOf(content, page.getTitle()).isPresent();
        if (redirect) {
            map.put("redirect", v2 ? Boolean.TRUE : "");
        }
        Map<String, Object> protection = new LinkedHashMap<>();
        protection.put("type", "edit");
        protection.put("level", Boolean.TRUE.equals(page.getProtectedPage()) ? "sysop" : "all");
        protection.put("expiry", "infinity");
        map.put("protection", List.of(protection));
    }

    private void revisionsProp(Map<String, Object> map, WikiPage page, Set<Long> revIdFilter,
                               Map<String, String> params, WikiUser user, boolean v2,
                               Map<String, Object> continueMap) {
        List<WikiRevision> originalHistory = wikiPageService.getHistory(page.getId()).stream()
                .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                .toList();
        if (!revIdFilter.isEmpty()) {
            List<WikiRevision> filtered = originalHistory.stream()
                    .filter(revision -> revIdFilter.contains(revision.getId()))
                    .toList();
            if (filtered.isEmpty()) {
                return;
            }
            originalHistory = filtered;
        }
        List<String> rvprop = splitComma(params.get("rvprop"));
        if (rvprop.isEmpty()) {
            rvprop = List.of("ids", "timestamp", "user", "comment");
        }
        int limit = intParam(params.get("rvlimit"), revIdFilter.isEmpty() ? 1 : 500, 1, 500);
        boolean newer = "newer".equalsIgnoreCase(Strings.trimToEmpty(params.get("rvdir")));
        LocalDateTime start = parseTime(params.get("rvstart"));
        LocalDateTime end = parseTime(params.get("rvend"));
        Long rvcontinue = parseLong(params.get("rvcontinue"));

        List<WikiRevision> filtered = new ArrayList<>();
        for (WikiRevision revision : originalHistory) { // desc order
            if (start != null && revision.getCreateAt().isBefore(start)) {
                continue;
            }
            if (end != null && revision.getCreateAt().isAfter(end)) {
                continue;
            }
            if (rvcontinue != null) {
                if (newer ? revision.getId() <= rvcontinue : revision.getId() >= rvcontinue) {
                    continue;
                }
            }
            filtered.add(revision);
            if (filtered.size() >= limit) {
                break;
            }
        }
        if (newer) {
            filtered.sort(Comparator.comparing(WikiRevision::getId));
        }
        boolean truncated = originalHistory.size() > filtered.size()
                && !filtered.isEmpty() && filtered.size() >= limit;
        if (truncated && !filtered.isEmpty()) {
            continueMap.put("rvcontinue", String.valueOf(filtered.get(newer ? filtered.size() - 1 : 0).getId()));
        }

        Map<Long, Long> parentIds = parentIds(originalHistory);
        List<Long> userIds = filtered.stream().map(WikiRevision::getUserId).filter(Objects::nonNull).toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of() : userService.usernames(userIds);
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (WikiRevision revision : filtered) {
            revisions.add(revisionMap(revision, rvprop, names, parentIds, v2));
        }
        map.put("revisions", revisions);
    }

    private Map<String, Object> revisionMap(WikiRevision revision, List<String> props,
                                            Map<Long, String> names, Map<Long, Long> parentIds, boolean v2) {
        Map<String, Object> item = new LinkedHashMap<>();
        String content = revision.getContent() == null ? "" : revision.getContent();
        for (String prop : props) {
            switch (prop) {
                case "ids" -> {
                    item.put("revid", revision.getId());
                    Long parent = parentIds.get(revision.getId());
                    if (parent != null) {
                        item.put("parentid", parent);
                    }
                }
                case "timestamp" -> item.put("timestamp", timestamp(revision.getCreateAt()));
                case "user" -> {
                    if (revision.getUserId() == null) {
                        String source = Strings.trimToEmpty(revision.getUserText());
                        if (source.isEmpty()) {
                            item.put("user", anonName());
                            item.put("anon", v2 ? Boolean.TRUE : "");
                        } else {
                            item.put("user", source);
                        }
                    } else {
                        item.put("user", names.getOrDefault(revision.getUserId(), ""));
                    }
                }
                case "userid" -> {
                    if (revision.getUserId() != null) {
                        item.put("userid", revision.getUserId());
                    }
                }
                case "comment" -> item.put("comment", Strings.trimToEmpty(revision.getComment()));
                case "content" -> {
                    item.put("contentmodel", "wikitext");
                    item.put("contentformat", "text/x-wiki");
                    item.put(v2 ? "content" : "*", content);
                }
                case "size" -> item.put("size", WikiSha1.bytes(content));
                case "sha1" -> item.put("sha1", WikiSha1.base36(content));
                case "tags" -> {
                    List<String> tags = splitComma(revision.getTags());
                    if (!tags.isEmpty()) {
                        item.put("tags", tags);
                    }
                }
                default -> {
                }
            }
        }
        if (!props.contains("content") && !props.contains("ids") && !props.contains("timestamp")
                && !props.contains("user")) {
            // 没请求任何可展示字段时补最小信息，避免空对象
            item.put("revid", revision.getId());
            item.put("timestamp", timestamp(revision.getCreateAt()));
        }
        return item;
    }

    private void categoriesProp(Map<String, Object> map, WikiPage page, boolean v2) {
        List<Map<String, Object>> categories = pageCategoryService.categoriesOf(page.getTitle()).stream()
                .map(ref -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ns", 14);
                    item.put("title", ref.title());
                    return item;
                })
                .toList();
        map.put("categories", categories);
    }

    private void imageInfoProp(Map<String, Object> map, WikiPage page, Map<String, String> params, boolean v2) {
        String name = FileTitles.fileNameOf(page.getTitle());
        if (name == null) {
            return;
        }
        WikiFile file = fileService.findByName(name).orElse(null);
        if (file == null) {
            return;
        }
        List<String> props = splitComma(params.get("iiprop"));
        if (props.isEmpty()) {
            props = List.of("url", "size", "mime");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (String prop : props) {
            switch (prop) {
                case "url" -> {
                    item.put("url", baseUrl(requestOfPage()) + fileService.urlOf(file));
                    item.put("thumburl", baseUrl(requestOfPage()) + fileService.thumbnailUrl(file, 320));
                    item.put("descriptionurl", baseUrl(requestOfPage())
                            + fileService.pageUrlOf(file).substring(1));
                }
                case "size" -> item.put("size", file.getSize());
                case "mime" -> item.put("mime", file.getContentType());
                case "timestamp" -> item.put("timestamp", timestamp(file.getCreatedAt()));
                case "user" -> {
                    if (file.getUploaderId() != null) {
                        Map<Long, String> names = userService.usernames(List.of(file.getUploaderId()));
                        item.put("user", names.getOrDefault(file.getUploaderId(), ""));
                        item.put("userid", file.getUploaderId());
                    }
                }
                case "sha1" -> item.put("sha1", "");
                default -> {
                }
            }
        }
        map.put("imageinfo", List.of(item));
    }

    // ==================== list 动作 ====================

    private ListResult allpages(Map<String, String> params, WikiUser user, boolean v2) {
        int ns = intParam(params.get("apnamespace"), 0, -2, 3000);
        int limit = intParam(params.get("aplimit"), 10, 1, 500);
        boolean descending = "descending".equalsIgnoreCase(Strings.trimToEmpty(params.get("apdir")));
        String from = normalizeTitle(Strings.trimToEmpty(params.get("apfrom")));
        String to = normalizeTitle(Strings.trimToEmpty(params.get("apto")));
        String cursor = normalizeTitle(Strings.trimToEmpty(params.get("apcontinue")));

        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderBy(true, !descending, WikiPage::getTitle)
                .last("LIMIT 5000"));
        List<Map<String, Object>> items = new ArrayList<>();
        String next = null;
        for (WikiPage page : pages) {
            String title = page.getTitle();
            if (!ActionApiNamespaces.matches(title, ns)) {
                continue;
            }
            if (!from.isEmpty() && title.compareToIgnoreCase(from) < 0) {
                continue;
            }
            if (!to.isEmpty() && title.compareToIgnoreCase(to) > 0) {
                continue;
            }
            if (!cursor.isEmpty()) {
                int cmp = title.compareToIgnoreCase(cursor);
                if (descending ? cmp >= 0 : cmp <= 0) {
                    continue;
                }
            }
            if (!accessControlService.canRead(title, user)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pageid", page.getId());
            item.put("ns", ActionApiNamespaces.idOf(title));
            item.put("title", title);
            items.add(item);
            if (items.size() >= limit) {
                next = title;
                break;
            }
        }
        Map<String, Object> cont = next == null ? null : Map.of("apcontinue", next);
        return new ListResult(items, cont);
    }

    private ListResult searchList(Map<String, String> params, WikiUser user, HttpServletRequest request) {
        String keyword = Strings.trimToEmpty(params.get("srsearch"));
        int limit = intParam(params.get("srlimit"), 10, 1, 500);
        int offset = intParam(params.get("sroffset"), 0, 0, Integer.MAX_VALUE);
        if (keyword.isEmpty()) {
            throw new ApiError("missingparam", "The srsearch parameter must be set.");
        }
        List<SearchHit> hits = searchService.search(keyword, offset + limit);
        List<SearchHit> readable = hits.stream()
                .filter(hit -> accessControlService.canRead(hit.title(), user))
                .toList();
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (SearchHit hit : readable) {
            if (index++ < offset) {
                continue;
            }
            if (items.size() >= limit) {
                break;
            }
            WikiPage page = wikiPageService.getVisibleByTitle(hit.title()).orElse(null);
            String content = page == null ? "" : wikiPageService.getLatestContent(page.getId()).orElse("");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ns", ActionApiNamespaces.idOf(hit.title()));
            item.put("title", hit.title());
            if (page != null) {
                item.put("pageid", page.getId());
            }
            item.put("size", WikiSha1.bytes(content));
            item.put("wordcount", content.isBlank() ? 0 : content.trim().split("\\s+").length);
            item.put("timestamp", page == null || page.getUpdateAt() == null
                    ? "" : timestamp(page.getUpdateAt()));
            item.put("snippet", Strings.trimToEmpty(hit.snippet()));
            items.add(item);
        }
        Map<String, Object> cont = readable.size() > offset + items.size()
                ? Map.of("sroffset", String.valueOf(offset + items.size()))
                : null;
        return new ListResult(items, cont);
    }

    private ListResult recentChanges(Map<String, String> params, WikiUser user, boolean v2) {
        int limit = intParam(params.get("rclimit"), 50, 1, 500);
        List<Integer> namespaces = intList(params.get("rcnamespace"));
        boolean newer = "newer".equalsIgnoreCase(Strings.trimToEmpty(params.get("rcdir")));
        LocalDateTime cursor = parseTime(params.get("rccontinue"));
        List<String> rvprop = splitComma(params.get("rcprop"));
        if (rvprop.isEmpty()) {
            rvprop = List.of("title", "ids", "sizes", "flags", "user", "timestamp", "comment");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT " + Math.min(2000, limit * 10)));
        Map<Long, WikiPage> pageCache = new LinkedHashMap<>();
        for (WikiRevision revision : revisions) {
            WikiPage page = pageCache.computeIfAbsent(revision.getPageId(), wikiPageMapper::selectById);
            if (page == null || Boolean.TRUE.equals(page.getDeleted())
                    || Boolean.TRUE.equals(page.getHided())) {
                continue;
            }
            if (!namespaces.isEmpty() && !namespaces.contains(ActionApiNamespaces.idOf(page.getTitle()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", revision.getCreateAt());
            row.put("sort", revision.getId());
            row.put("kind", "edit");
            row.put("revision", revision);
            row.put("page", page);
            rows.add(row);
        }
        for (WikiLog log : logService.recent(null, null, false, Math.min(2000, limit * 10))) {
            String logTarget = Strings.trimToEmpty(log.getTarget());
            if (!logTarget.isEmpty() && wikiPageService.getVisibleByTitle(logTarget).isEmpty()) {
                continue;
            }
            if (!namespaces.isEmpty() && !namespaces.contains(ActionApiNamespaces.idOf(logTarget))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", log.getTime());
            row.put("sort", log.getId());
            row.put("kind", "log");
            row.put("log", log);
            row.put("page", log.getTarget() == null ? null : pageCache.computeIfAbsent(-1L * log.getId(),
                    id -> wikiPageService.getVisibleByTitle(log.getTarget()).orElse(null)));
            rows.add(row);
        }
        rows.sort((a, b) -> {
            LocalDateTime left = (LocalDateTime) a.get("time");
            LocalDateTime right = (LocalDateTime) b.get("time");
            int cmp = right.compareTo(left);
            return newer ? -cmp : cmp;
        });

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            LocalDateTime time = (LocalDateTime) row.get("time");
            if (cursor != null) {
                if (newer ? !time.isAfter(cursor) : !time.isBefore(cursor)) {
                    continue;
                }
            }
            WikiPage page = (WikiPage) row.get("page");
            String target = page != null ? page.getTitle()
                    : (row.get("log") instanceof WikiLog log ? Strings.trimToEmpty(log.getTarget()) : "");
            if (target.isEmpty() || !accessControlService.canRead(target, user)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if ("log".equals(row.get("kind"))) {
                WikiLog log = (WikiLog) row.get("log");
                if (rvprop.contains("title")) {
                    item.put("ns", ActionApiNamespaces.idOf(target));
                    item.put("title", target);
                }
                item.put("type", "log");
                item.put("logid", log.getId());
                item.put("logtype", log.getLogType());
                item.put("logaction", "\u4e8b\u4ef6");
                if (page != null) {
                    item.put("pageid", page.getId());
                }
                rowUser(item, log.getActorUserId(), null, rvprop);
                if (rvprop.contains("timestamp")) {
                    item.put("timestamp", timestamp(log.getTime()));
                }
                if (rvprop.contains("comment")) {
                    item.put("comment", Strings.trimToEmpty(log.getDetail()));
                }
            } else {
                WikiRevision revision = (WikiRevision) row.get("revision");
                if (rvprop.contains("title")) {
                    item.put("ns", ActionApiNamespaces.idOf(target));
                    item.put("title", target);
                }
                item.put("type", "edit");
                if (rvprop.contains("ids")) {
                    item.put("rcid", revision.getId());
                    item.put("revid", revision.getId());
                    item.put("old_revid", parentId(revision));
                    item.put("pageid", page.getId());
                }
                rowUser(item, revision.getUserId(), revision.getUserText(), rvprop);
                if (rvprop.contains("timestamp")) {
                    item.put("timestamp", timestamp(revision.getCreateAt()));
                }
                if (rvprop.contains("comment")) {
                    item.put("comment", Strings.trimToEmpty(revision.getComment()));
                }
                if (rvprop.contains("sizes")) {
                    int newLength = WikiSha1.bytes(revision.getContent());
                    WikiRevision previous = wikiPageService.getPreviousRevision(page.getId(), revision.getId())
                            .orElse(null);
                    int oldLength = previous == null ? 0 : WikiSha1.bytes(previous.getContent());
                    item.put("oldlen", oldLength);
                    item.put("newlen", newLength);
                    item.put("size", newLength);
                }
                if (rvprop.contains("flags")) {
                    if (Boolean.TRUE.equals(revision.getBot())) {
                        item.put("bot", v2 ? Boolean.TRUE : "");
                    }
                    if (Boolean.TRUE.equals(revision.getMinor())) {
                        item.put("minor", v2 ? Boolean.TRUE : "");
                    }
                    List<String> tags = splitComma(revision.getTags());
                    if (!tags.isEmpty()) {
                        item.put("tags", tags);
                    }
                }
            }
            items.add(item);
            if (items.size() >= limit) {
                LocalDateTime nextCursor = (LocalDateTime) row.get("time");
                Map<String, Object> cont = Map.of("rccontinue", timestamp(nextCursor));
                return new ListResult(items, cont);
            }
        }
        return new ListResult(items, null);
    }

    private void rowUser(Map<String, Object> item, Long userId, String userText, List<String> rvprop) {
        if (!rvprop.contains("user")) {
            return;
        }
        if (userId == null) {
            String source = Strings.trimToEmpty(userText);
            if (source.isEmpty()) {
                item.put("user", anonName());
                item.put("anon", Boolean.TRUE);
            } else {
                item.put("user", source);
            }
        } else {
            item.put("user", userService.usernames(List.of(userId)).getOrDefault(userId, ""));
            item.put("userid", userId);
        }
    }

    private ListResult users(Map<String, String> params, boolean v2) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String raw : splitPipe(params.get("ususers"))) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            WikiUser user = userService.findByUsername(name).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            if (user == null) {
                item.put("name", name);
                item.put("missing", v2 ? Boolean.TRUE : "");
            } else {
                item.put("userid", user.getId());
                item.put("name", user.getUsername());
                List<String> groups = new ArrayList<>(permissionService.namesOf(user.getId()));
                if (!groups.contains(PermissionService.GROUP_USER)) {
                    groups.add(0, PermissionService.GROUP_USER);
                }
                item.put("groups", groups);
                item.put("registration", timestamp(user.getCreateAt()));
            }
            items.add(item);
        }
        return new ListResult(items, null);
    }

    private ListResult userContribs(Map<String, String> params, WikiUser viewer, boolean v2) {
        String name = Strings.trimToEmpty(params.get("ucuser"));
        WikiUser target = userService.findByUsername(name).orElse(null);
        if (target == null) {
            throw new ApiError("nosuchuser", "The user \"" + name + "\" does not exist.");
        }
        int limit = intParam(params.get("uclimit"), 10, 1, 500);
        boolean descending = !"newer".equalsIgnoreCase(Strings.trimToEmpty(params.get("ucdir")));
        Long cursor = parseLong(params.get("uccontinue"));
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getUserId, target.getId())
                .eq(WikiRevision::getDeleted, false)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT 3000"));
        List<Map<String, Object>> items = new ArrayList<>();
        Long next = null;
        for (WikiRevision revision : revisions) {
            if (cursor != null && (descending ? revision.getId() >= cursor : revision.getId() <= cursor)) {
                continue;
            }
            WikiPage page = wikiPageMapper.selectById(revision.getPageId());
            if (page == null || Boolean.TRUE.equals(page.getDeleted())
                    || Boolean.TRUE.equals(page.getHided())) {
                continue;
            }
            if (!accessControlService.canRead(page.getTitle(), viewer)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("revid", revision.getId());
            item.put("parentid", parentId(revision));
            item.put("userid", target.getId());
            item.put("user", target.getUsername());
            item.put("timestamp", timestamp(revision.getCreateAt()));
            item.put("size", WikiSha1.bytes(revision.getContent()));
            item.put("comment", Strings.trimToEmpty(revision.getComment()));
            item.put("pageid", page.getId());
            item.put("ns", ActionApiNamespaces.idOf(page.getTitle()));
            item.put("title", page.getTitle());
            List<String> tags = splitComma(revision.getTags());
            if (!tags.isEmpty()) {
                item.put("tags", tags);
            }
            items.add(item);
            if (items.size() >= limit) {
                next = revision.getId();
                break;
            }
        }
        if (!descending) {
            java.util.Collections.reverse(items);
        }
        Map<String, Object> cont = next == null ? null : Map.of("uccontinue", String.valueOf(next));
        return new ListResult(items, cont);
    }

    private ListResult categoryMembers(Map<String, String> params, WikiUser user, boolean v2) {
        String title = normalizeTitle(Strings.trimToEmpty(params.get("cmtitle")));
        if (title.isEmpty()) {
            throw new ApiError("missingparam", "The cmtitle parameter must be set.");
        }
        if (!title.contains(":")) {
            title = "分类:" + title;
        }
        int limit = intParam(params.get("cmlimit"), 10, 1, 500);
        int offset = intParam(params.get("cmcontinue"), 0, 0, Integer.MAX_VALUE);
        List<PageCategoryService.Member> members = pageCategoryService.members(title, offset + limit + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (PageCategoryService.Member member : members) {
            if (index++ < offset) {
                continue;
            }
            if (items.size() >= limit) {
                break;
            }
            if (!accessControlService.canRead(member.page().getTitle(), user)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pageid", member.page().getId());
            item.put("ns", ActionApiNamespaces.idOf(member.page().getTitle()));
            item.put("title", member.page().getTitle());
            if (member.sortKey() != null && !member.sortKey().isEmpty()) {
                item.put("sortkey", member.sortKey());
            }
            items.add(item);
        }
        Map<String, Object> cont = members.size() > offset + items.size()
                ? Map.of("cmcontinue", String.valueOf(offset + items.size()))
                : null;
        return new ListResult(items, cont);
    }

    private ListResult allCategories(Map<String, String> params, boolean v2) {
        int limit = intParam(params.get("aclimit"), 10, 1, 500);
        int offset = intParam(params.get("accontinue"), 0, 0, Integer.MAX_VALUE);
        List<PageCategoryService.CategoryCount> categories = pageCategoryService.allCategories(offset + limit + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (PageCategoryService.CategoryCount category : categories) {
            if (index++ < offset) {
                continue;
            }
            if (items.size() >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (v2) {
                item.put("category", category.title());
            } else {
                item.put("*", category.title());
            }
            item.put("size", category.count());
            items.add(item);
        }
        Map<String, Object> cont = categories.size() > offset + items.size()
                ? Map.of("accontinue", String.valueOf(offset + items.size()))
                : null;
        return new ListResult(items, cont);
    }

    private ListResult logEvents(Map<String, String> params, WikiUser user, boolean v2) {
        int limit = intParam(params.get("lelimit"), 10, 1, 500);
        List<String> types = splitComma(params.get("letype"));
        String title = normalizeTitle(Strings.trimToEmpty(params.get("letitle")));
        Long cursor = parseLong(params.get("lecontinue"));
        List<Map<String, Object>> items = new ArrayList<>();
        Long next = null;
        for (WikiLog log : logService.search(types.isEmpty() ? null : String.join(",", types),
                null, title.isEmpty() ? null : title, null, null, false, 2000)) {
            if (cursor != null && log.getId() >= cursor) {
                continue;
            }
            String target = Strings.trimToEmpty(log.getTarget());
            if (!target.isEmpty() && (wikiPageService.getVisibleByTitle(target).isEmpty()
                    || !accessControlService.canRead(target, user))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("logid", log.getId());
            item.put("ns", ActionApiNamespaces.idOf(target));
            item.put("title", target);
            WikiPage page = target.isEmpty() ? null : wikiPageService.getVisibleByTitle(target).orElse(null);
            if (page != null) {
                item.put("pageid", page.getId());
            }
            item.put("logtype", log.getLogType());
            item.put("logaction", "\u4e8b\u4ef6");
            if (log.getActorUserId() == null) {
                item.put("user", anonName());
                item.put("anon", v2 ? Boolean.TRUE : "");
            } else {
                item.put("user", userService.usernames(List.of(log.getActorUserId()))
                        .getOrDefault(log.getActorUserId(), ""));
                item.put("userid", log.getActorUserId());
            }
            item.put("timestamp", timestamp(log.getTime()));
            item.put("comment", Strings.trimToEmpty(log.getDetail()));
            items.add(item);
            if (items.size() >= limit) {
                next = log.getId();
                break;
            }
        }
        Map<String, Object> cont = next == null ? null : Map.of("lecontinue", String.valueOf(next));
        return new ListResult(items, cont);
    }

    private ListResult allImages(Map<String, String> params, boolean v2, HttpServletRequest request) {
        int limit = intParam(params.get("ailimit"), 10, 1, 500);
        String cursor = Strings.trimToEmpty(params.get("aicontinue"));
        List<WikiFile> files = new ArrayList<>(fileService.all());
        files.sort(Comparator.comparing(WikiFile::getName, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> items = new ArrayList<>();
        String next = null;
        for (WikiFile file : files) {
            if (!cursor.isEmpty() && file.getName().compareToIgnoreCase(cursor) <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", file.getName());
            item.put("timestamp", timestamp(file.getCreatedAt()));
            item.put("url", baseUrl(request) + fileService.urlOf(file).substring(1));
            item.put("thumburl", baseUrl(request) + fileService.thumbnailUrl(file, 320).substring(1));
            item.put("descriptionurl", baseUrl(request) + fileService.pageUrlOf(file).substring(1));
            item.put("size", file.getSize());
            item.put("mime", file.getContentType());
            items.add(item);
            if (items.size() >= limit) {
                next = file.getName();
                break;
            }
        }
        Map<String, Object> cont = next == null ? null : Map.of("aicontinue", next);
        return new ListResult(items, cont);
    }

    // ==================== parse ====================

    private Object parse(Map<String, String> params, HttpServletRequest request, int fv) {
        boolean v2 = fv == 2;
        WikiUser user = UserSession.current(request);
        String title = normalizeTitle(Strings.trimToEmpty(params.get("page")));
        if (title.isEmpty()) {
            title = normalizeTitle(Strings.trimToEmpty(params.get("title")));
        }
        String text = params.get("text");
        if (title.isEmpty() && text == null) {
            throw new ApiError("missingparam", "The page or text parameter must be set.");
        }
        if (title.isEmpty()) {
            title = "\u63a5\u53e3\u89e3\u6790";
        }
        WikiPage page = title.isEmpty() ? null : wikiPageService.getVisibleByTitle(title).orElse(null);
        if (page != null && !accessControlService.canRead(title, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to read this page.");
        }
        String content = text != null ? text
                : (page == null ? "" : wikiPageService.getLatestContent(page.getId()).orElse(""));
        List<String> props = splitComma(params.get("prop"));
        if (props.isEmpty()) {
            props = List.of("text");
        }
        Map<String, Object> parse = new LinkedHashMap<>();
        parse.put("title", title);
        if (page != null) {
            parse.put("pageid", page.getId());
        }
        parse.put("ns", ActionApiNamespaces.idOf(title));
        WikitextRenderer.RenderResult renderResult = wikitextRenderer.renderWithMeta(content, title);
        for (String prop : props) {
            switch (prop) {
                case "wikitext" -> parse.put("wikitext", content);
                case "text" -> parse.put("text", v2 ? renderResult.html() : Map.of("*", renderResult.html()));
                case "displaytitle" -> parse.put("displaytitle",
                        renderResult.displayTitle() == null ? title : renderResult.displayTitle());
                case "categories" -> {
                    List<Map<String, Object>> categories = renderResult.categories().stream()
                            .map(ref -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("sortkey", ref.sortKey() == null ? "" : ref.sortKey());
                                item.put("ns", 14);
                                item.put("title", ref.title());
                                return item;
                            })
                            .toList();
                    parse.put("categories", categories);
                }
                default -> {
                }
            }
        }
        parse.put("lang", locale().getLanguage());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("parse", parse);
        return root;
    }

    // ==================== edit ====================

    private Map<String, Object> edit(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            throw new ApiError("notloggedin", "Anonymous users cannot use this wiki's API to edit; please log in.");
        }
        String providedToken = Strings.trimToEmpty(params.get("token"));
        if (!ActionApiTokens.valid(request, ActionApiTokens.csrf(request), providedToken)) {
            throw new ApiError("badtoken", "Invalid CSRF token.");
        }
        String section = Strings.trimToEmpty(params.get("section"));
        String title = normalizeTitle(Strings.trimToEmpty(params.get("title")));
        if (title.isEmpty()) {
            throw new ApiError("missingparam", "The title parameter must be set.");
        }
        // 非法字符 / 过长 / 相对路径等：仿 MediaWiki 返回 invalidtitle
        Optional<WikiTitles.TitleError> titleError = WikiTitles.validate(Strings.trimToEmpty(params.get("title")));
        if (titleError.isPresent()) {
            throw new ApiError("invalidtitle",
                    "Bad title \"" + Strings.trimToEmpty(params.get("title")) + "\".");
        }
        boolean hasText = params.containsKey("text");
        String appendText = params.get("appendtext");
        if (!hasText && appendText == null) {
            throw new ApiError("missingparam", "The text parameter must be set.");
        }
        Optional<WikiBlock> block = blockService.currentBlock(user);
        if (block.isPresent()) {
            throw new ApiError("blocked", blockService.notice(block.get()));
        }
        WikiPage existing = wikiPageService.getByTitle(title).orElse(null);
        if (existing == null && "1".equals(Strings.trimToEmpty(params.get("nocreate")))) {
            throw new ApiError("nocreate-missing", "The page you specified doesn't exist.");
        }
        if (existing != null && "1".equals(Strings.trimToEmpty(params.get("createonly")))) {
            throw new ApiError("articleexists", "The article you tried to create has been created already.");
        }
        AccessAction action = existing == null ? AccessAction.CREATE : AccessAction.EDIT;
        if (!accessControlService.can(action, title, user)) {
            throw new ApiError("permissiondenied",
                    existing == null ? "You do not have permission to create this page."
                            : "You do not have permission to edit this page.");
        }
        if (existing != null && Boolean.TRUE.equals(existing.getProtectedPage())
                && !permissionService.canManage(user.getId())) {
            throw new ApiError("protectedpage", "This page has been protected; only administrators can edit it.");
        }
        Optional<TitleBlacklistService.Violation> violation = titleBlacklistService.check(
                title, existing == null ? TitleBlacklistService.Operation.CREATE
                        : TitleBlacklistService.Operation.EDIT, user);
        if (violation.isPresent()) {
            throw new ApiError("titleblacklisted", "The title is blacklisted.");
        }
        Long baserevid = parseLong(params.get("baserevid"));
        if (existing != null && baserevid != null && !Objects.equals(baserevid, existing.getLatestRevisionId())) {
            throw new ApiError("editconflict", "Edit conflict detected; reload the page and try again.");
        }
        String oldText = existing == null ? "" : wikiPageService.getLatestContent(existing.getId()).orElse("");
        String submittedText = Strings.trimToEmpty(params.get("text"));
        boolean expandSignatures = true;
        String content;
        if (section.isEmpty()) {
            if (hasText) {
                content = submittedText;
            } else {
                // appendtext 只展开本次追加的部分，不能重签旧正文里别人的历史签名。
                content = oldText + SignatureExpander.expand(appendText, user, LocalDateTime.now());
                expandSignatures = false;
            }
        } else {
            // 段落编辑：只展开新提交的段落，再替换进旧正文。
            content = applySection(existing, oldText, section, params,
                    SignatureExpander.expand(submittedText, user, LocalDateTime.now()));
            expandSignatures = false;
        }
        String summary = Strings.trimToEmpty(params.get("summary"));
        // 保存前钩子（EditGuard，如 ConfirmEdit 的验证码、插件版滥用过滤器）：来源 source=api
        // （迁移前 api.php 不受 ConfirmEdit 影响，所以验证码钩子在 api 来源直接放行）
        RequestEditContext apiEditContext = RequestEditContext.api(
                request, viewerOf(user), new RequestEditContext.FormValues(title, existing == null,
                        content, summary, "1".equals(Strings.trimToEmpty(params.get("minor"))),
                        existing == null ? EditContext.ACTION_CREATE : EditContext.ACTION_EDIT,
                        baserevid, params, false));
        EditHookService.Decision editDecision = editHookService.beforeSave(apiEditContext);
        if (editDecision.blocked()) {
            throw new ApiError("editguard-blocked", editDecision.message().isEmpty()
                    ? "This edit was rejected by an extension." : editDecision.message());
        }
        if (editDecision.handled()) {
            // 钩子已自行处理（例如写进审核队列）：不保存，按 API 约定返回成功 + handled 标记
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("result", "Success");
            if (existing != null) {
                edit.put("pageid", existing.getId());
                edit.put("title", existing.getTitle());
            }
            edit.put("handled", true);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("edit", edit);
            return root;
        }
        boolean bot = "1".equals(Strings.trimToEmpty(params.get("bot")))
                && permissionService.has(user.getId(), PermissionService.GROUP_BOT);
        boolean minor = "1".equals(Strings.trimToEmpty(params.get("minor")));
        if (existing != null && oldText.equals(content)) {
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("result", "Success");
            edit.put("pageid", existing.getId());
            edit.put("title", existing.getTitle());
            edit.put("nochange", "");
            edit.put("oldrevid", existing.getLatestRevisionId());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("edit", edit);
            return root;
        }
        Long oldRevId = existing == null ? null : existing.getLatestRevisionId();
        WikiPage saved;
        if (existing != null && oldRevId != null) {
            saved = wikiPageService.editPageIfLatest(user, title, content, summary, bot, minor,
                    changeTags(apiEditContext), oldRevId, expandSignatures);
            if (saved == null) {
                throw new ApiError("editconflict", "Edit conflict detected; reload the page and try again.");
            }
        } else {
            saved = wikiPageService.editPage(user, title, content, summary, bot, minor,
                    changeTags(apiEditContext), expandSignatures);
        }
        WikiRevision revision = saved == null || saved.getLatestRevisionId() == null ? null
                : wikiPageService.getRevision(saved.getLatestRevisionId()).orElse(null);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("result", "Success");
        if (saved != null) {
            edit.put("pageid", saved.getId());
            edit.put("title", saved.getTitle());
        }
        edit.put("contentmodel", "wikitext");
        if (oldRevId != null) {
            edit.put("oldrevid", oldRevId);
        }
        if (revision != null) {
            edit.put("newrevid", revision.getId());
            edit.put("newtimestamp", timestamp(revision.getCreateAt()));
        }
        if (!editDecision.warnings().isEmpty()) {
            // 保存前钩子的 WARN：保存成功 + 提示（网页侧显示在目标页面顶部）
            edit.put("warnings", editDecision.warnings());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("edit", edit);
        return root;
    }

    /** 当前 API 用户视图（供插件保存前钩子判断用户组；匿名返回 {@link Viewer#ANONYMOUS}）。 */
    private Viewer viewerOf(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = permissionService.namesOf(user.getId());
        return new Viewer(user.getId(), user.getUsername(), false, groups,
                groups.contains(PermissionService.GROUP_BOT));
    }

    /** 保存前钩子登记的变更标签 → 版本的 {@code tags} 字段（逗号分隔；如滥用过滤器的 tag 操作）。 */
    private static String changeTags(RequestEditContext context) {
        return context == null ? "" : String.join(",", context.tags());
    }

    /** {@code action=edit} 的 {@code section} 参数（仿 MediaWiki）：0=导语、N=第 N 段、{@code new}=追加新段落。 */
    private String applySection(WikiPage existing, String oldText, String section,
                                Map<String, String> params, String submittedText) {
        if (existing == null) {
            throw new ApiError("nosuchsection", "There is no section " + section + ".");
        }
        if ("new".equalsIgnoreCase(section)) {
            String heading = Strings.trimToEmpty(params.get("sectiontitle"));
            String text = submittedText;
            StringBuilder out = new StringBuilder(oldText);
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            if (!heading.isEmpty()) {
                out.append("== ").append(heading).append(" ==\n");
            }
            out.append(text);
            return out.toString();
        }
        int index;
        try {
            index = Integer.parseInt(section);
        } catch (NumberFormatException exception) {
            throw new ApiError("badvalue", "Invalid section id.");
        }
        if (WikiSections.extract(oldText, index) == null) {
            throw new ApiError("nosuchsection", "There is no section " + index + ".");
        }
        return WikiSections.replace(oldText, index, submittedText);
    }

    // ==================== 管理动作：move / delete / protect / block / unblock / upload ====================

    /** 写操作公共前置：必须登录 + 有效 CSRF token + 未被封禁。 */
    private WikiUser apiUser(HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            throw new ApiError("notloggedin", "Anonymous users cannot use this wiki's API to perform this action; please log in.");
        }
        return user;
    }

    private void requireCsrf(Map<String, String> params, HttpServletRequest request) {
        if (!ActionApiTokens.valid(request, ActionApiTokens.csrf(request), Strings.trimToEmpty(params.get("token")))) {
            throw new ApiError("badtoken", "Invalid CSRF token.");
        }
    }

    private void rejectBlocked(WikiUser user) {
        blockService.currentBlock(user).ifPresent(block -> {
            throw new ApiError("blocked", blockService.notice(block));
        });
    }

    private static boolean isApiOn(String value) {
        String text = Strings.trimToEmpty(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    /** {@code action=move}：移动页面（默认留下重定向，{@code noredirect=1} 不留）。 */
    private Map<String, Object> move(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        rejectBlocked(user);
        String from = Strings.trimToEmpty(params.get("from"));
        String to = Strings.trimToEmpty(params.get("to"));
        if (from.isEmpty()) {
            throw new ApiError("missingparam", "The from parameter must be set.");
        }
        if (to.isEmpty()) {
            throw new ApiError("missingparam", "The to parameter must be set.");
        }
        WikiTitles.validate(to).ifPresent(error -> {
            throw new ApiError("invalidtitle", "Bad title \"" + to + "\".");
        });
        if (WikiTitles.normalize(from).equals(WikiTitles.normalize(to))) {
            throw new ApiError("selfmove", "The page title is the same; can't move.");
        }
        WikiPage page = wikiPageService.getByTitle(from)
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
        if (!accessControlService.can(AccessAction.MOVE, from, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to move this page.");
        }
        if (!accessControlService.can(AccessAction.CREATE, to, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to create the target page.");
        }
        if (wikiPageService.getByTitle(to).isPresent()) {
            throw new ApiError("articleexists", "The target page already exists.");
        }
        if (Boolean.TRUE.equals(page.getProtectedPage()) && !permissionService.canManage(user.getId())) {
            throw new ApiError("protectedpage", "This page has been protected; only administrators can move it.");
        }
        String reason = Strings.trimToEmpty(params.get("reason"));
        boolean leaveRedirect = !"1".equals(Strings.trimToEmpty(params.get("noredirect")));
        WikiPage moved = wikiPageService.movePage(user, page.getTitle(), to, leaveRedirect);
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("from", page.getTitle());
        move.put("to", moved == null ? to : moved.getTitle());
        move.put("reason", reason);
        move.put("redirectcreated", leaveRedirect);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("move", move);
        return root;
    }

    /** {@code action=delete}：软删除页面（回收站可恢复）。 */
    private Map<String, Object> delete(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        rejectBlocked(user);
        String title = Strings.trimToEmpty(params.get("title"));
        if (title.isEmpty()) {
            throw new ApiError("missingparam", "The title parameter must be set.");
        }
        WikiPage page = wikiPageService.getByTitle(title)
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
        if (!accessControlService.can(AccessAction.DELETE, page.getTitle(), user)) {
            throw new ApiError("permissiondenied", "You do not have permission to delete this page.");
        }
        if (Boolean.TRUE.equals(page.getProtectedPage()) && !permissionService.canManage(user.getId())) {
            throw new ApiError("protectedpage", "This page has been protected; only administrators can delete it.");
        }
        String reason = Strings.trimToEmpty(params.get("reason"));
        wikiPageService.deletePage(user, page.getTitle());
        Map<String, Object> delete = new LinkedHashMap<>();
        delete.put("title", page.getTitle());
        delete.put("reason", reason);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("delete", delete);
        return root;
    }

    /** {@code action=protect}：保护 / 解除保护（{@code protections=edit=sysop} 或 {@code edit=all}）。 */
    private Map<String, Object> protect(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        rejectBlocked(user);
        String title = Strings.trimToEmpty(params.get("title"));
        if (title.isEmpty()) {
            throw new ApiError("missingparam", "The title parameter must be set.");
        }
        WikiPage page = wikiPageService.getByTitle(title)
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
        if (!accessControlService.can(AccessAction.PROTECT, page.getTitle(), user)) {
            throw new ApiError("permissiondenied", "You do not have permission to protect this page.");
        }
        String protections = Strings.trimToEmpty(params.get("protections"));
        boolean protect = protections.isEmpty()
                ? isApiOn(params.get("protect"))
                : !protections.toLowerCase(Locale.ROOT).contains("edit=all");
        wikiPageService.setProtected(user, page.getTitle(), protect);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", page.getTitle());
        result.put("protections", protect ? "edit=sysop" : "edit=all");
        result.put("expiry", "infinity");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("protect", result);
        return root;
    }

    /** {@code action=block}：封禁用户 / IP（同目标已有生效封禁时更新）。 */
    private Map<String, Object> block(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        if (!accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to block users.");
        }
        String target = Strings.trimToEmpty(params.getOrDefault("user", params.get("target")));
        if (target.isEmpty()) {
            throw new ApiError("missingparam", "The user parameter must be set.");
        }
        String expiry = Strings.trimToEmpty(params.get("expiry"));
        if (!BlockExpiry.isKnown(expiry)) {
            throw new ApiError("badvalue", "Invalid expiry value.");
        }
        String reason = Strings.trimToEmpty(params.get("reason"));
        WikiBlock block = blockService.block(user, target, BlockExpiry.resolve(expiry, LocalDateTime.now()),
                reason, isApiOn(params.get("noemail")), isApiOn(params.get("nocreate")), isApiOn(params.get("anononly")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", block.getTarget());
        result.put("id", block.getId());
        result.put("reason", reason);
        result.put("expiry", expiry.isEmpty() ? "infinite" : expiry);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("block", result);
        return root;
    }

    /** {@code action=unblock}：解除封禁（按目标找生效中的封禁）。 */
    private Map<String, Object> unblock(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        if (!accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to unblock users.");
        }
        String target = Strings.trimToEmpty(params.getOrDefault("user", params.get("target")));
        if (target.isEmpty()) {
            throw new ApiError("missingparam", "The user parameter must be set.");
        }
        WikiBlock block = blockService.findActiveByTarget(target)
                .orElseThrow(() -> new ApiError("notblocked", "The user is not blocked."));
        blockService.unblock(user, block.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", block.getTarget());
        result.put("id", block.getId());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("unblock", result);
        return root;
    }

    /** {@code action=upload}：multipart 上传（字段 {@code file}，可选 {@code filename} / {@code comment} / {@code text}）。 */
    private Map<String, Object> upload(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        rejectBlocked(user);
        byte[] bytes = null;
        String submittedName = null;
        org.springframework.web.multipart.MultipartHttpServletRequest multipart =
                org.springframework.web.util.WebUtils.getNativeRequest(request,
                        org.springframework.web.multipart.MultipartHttpServletRequest.class);
        if (multipart != null) {
            org.springframework.web.multipart.MultipartFile file = multipart.getFile("file");
            if (file != null && !file.isEmpty()) {
                try {
                    bytes = file.getBytes();
                } catch (Exception exception) {
                    bytes = null;
                }
                submittedName = file.getOriginalFilename();
            }
        } else {
            Part part = multipartPart(request, "file");
            if (part != null && part.getSize() > 0) {
                try (var input = part.getInputStream()) {
                    bytes = input.readAllBytes();
                } catch (Exception exception) {
                    bytes = null;
                }
                submittedName = part.getSubmittedFileName();
            }
        }
        if (bytes == null || bytes.length == 0) {
            throw new ApiError("nofile", "The file is missing.");
        }
        String name = FileTitles.normalizeName(Strings.trimToEmpty(params.get("filename")));
        if (name.isEmpty()) {
            name = FileTitles.normalizeName(submittedName);
        }
        if (name.isEmpty()) {
            throw new ApiError("nofile", "The file is missing.");
        }
        WikiTitles.validate(name).ifPresent(error -> {
            throw new ApiError("illegal-filename", "The filename is not allowed.");
        });
        String finalName = name;
        if (!accessControlService.can(AccessAction.UPLOAD, "文件:" + name, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to upload this file.");
        }
        // 动作前钩子（ActionGuard，如审核的上传排队 / 插件版滥用过滤器的 action=upload）：
        // 载荷是文件内容，HANDLED = 钩子已自行处理（文件进队列），core 不再写入正式存储
        ActionHookService.Decision uploadDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, viewerOf(user), "upload")
                .apiSource()
                .target("文件:" + finalName)
                .summary(Strings.trimToEmpty(params.get("comment")))
                .data("name", finalName)
                .data("size", bytes.length)
                .data("description", Strings.trimToEmpty(params.get("comment")))
                .payload(bytes)
                .build());
        if (uploadDecision.blocked()) {
            throw new ApiError("upload-blocked", uploadDecision.message().isEmpty()
                    ? "This upload was rejected by an extension." : uploadDecision.message());
        }
        try {
            String comment = Strings.trimToEmpty(params.get("comment"));
            if (uploadDecision.handled()) {
                // 钩子已自行处理（例如审核：文件进队列，通过后才写入磁盘）
                Map<String, Object> upload = new LinkedHashMap<>();
                upload.put("result", "Success");
                upload.put("filename", finalName);
                upload.put("size", bytes.length);
                upload.putAll(uploadDecision.data());
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("upload", upload);
                return root;
            }
            WikiFile file = fileService.store(user, finalName, comment, bytes);
            String text = Strings.trimToEmpty(params.get("text"));
            String descriptionTitle = FileTitles.titleOf(file.getName());
            if (!text.isEmpty() && wikiPageService.getByTitle(descriptionTitle).isEmpty()) {
                wikiPageService.editPage(user, descriptionTitle, text, "API 上传文件");
            }
            Map<String, Object> upload = new LinkedHashMap<>();
            upload.put("result", "Success");
            upload.put("filename", file.getName());
            upload.put("size", file.getSize());
            upload.put("mime", file.getContentType());
            upload.put("url", fileService.urlOf(file));
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("upload", upload);
            return root;
        } catch (Exception exception) {
            String key = Strings.trimToEmpty(exception.getMessage());
            throw new ApiError("upload-error", key.isEmpty() ? "The upload failed." : message(key));
        }
    }

    private static Part multipartPart(HttpServletRequest request, String name) {
        try {
            return request.getPart(name);
        } catch (Exception exception) {
            return null;
        }
    }

    /** {@code action=compare}（仿 MediaWiki 1.33+）：比较两个版本（fromrev/torev 或 fromtitle/totitle）。 */
    private Map<String, Object> compare(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = UserSession.current(request);
        WikiRevision from = resolveCompareRevision(params, "fromtitle", "fromrev", user);
        WikiRevision to = resolveCompareRevision(params, "totitle", "torev", user);
        if (from == null) {
            throw new ApiError("missingparam", "The fromrev or fromtitle parameter must be set.");
        }
        if (to == null) {
            throw new ApiError("missingparam", "The torev or totitle parameter must be set.");
        }
        String body = diffTable(from.getContent(), to.getContent());
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("fromtitle", titleOfRevision(from));
        compare.put("fromrevid", from.getId());
        compare.put("totitle", titleOfRevision(to));
        compare.put("torevid", to.getId());
        compare.put("body", fv == 2 ? body : Map.of("*", body));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compare", compare);
        return root;
    }

    private WikiRevision resolveCompareRevision(Map<String, String> params, String titleParam,
                                                String revParam, WikiUser user) {
        Long revId = parseLong(params.get(revParam));
        if (revId != null) {
            WikiRevision revision = wikiRevisionMapper.selectById(revId);
            if (revision == null || Boolean.TRUE.equals(revision.getDeleted())) {
                throw new ApiError("nosuchrevid", "There is no revision with ID " + revId + ".");
            }
            if (!accessControlService.canRead(titleOfRevision(revision), user)) {
                throw new ApiError("permissiondenied", "You do not have permission to read this page.");
            }
            return revision;
        }
        String title = normalizeTitle(Strings.trimToEmpty(params.get(titleParam)));
        if (title.isEmpty()) {
            return null;
        }
        if (!accessControlService.canRead(title, user)) {
            throw new ApiError("permissiondenied", "You do not have permission to read this page.");
        }
        WikiPage page = wikiPageService.getVisibleByTitle(title)
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
        return wikiPageService.getLatestRevision(page.getId())
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
    }

    private String titleOfRevision(WikiRevision revision) {
        WikiPage page = wikiPageMapper.selectById(revision.getPageId());
        return page == null ? "" : page.getTitle();
    }

    /** 差异表格（与网页差异页同一套行级 diff）。 */
    private static String diffTable(String oldText, String newText) {
        StringBuilder html = new StringBuilder("<table class=\"diff\"><tbody>");
        for (LineDiff.Line line : LineDiff.diff(oldText, newText)) {
            switch (line.type()) {
                case SAME -> html.append("<tr><td class=\"diff-marker\"></td><td class=\"diff-context\">")
                        .append(HtmlUtils.htmlEscape(line.text())).append("</td></tr>");
                case DELETE -> html.append("<tr><td class=\"diff-marker\">−</td><td class=\"diff-deletedline\">")
                        .append(HtmlUtils.htmlEscape(line.text())).append("</td></tr>");
                case ADD -> html.append("<tr><td class=\"diff-marker\">+</td><td class=\"diff-addedline\">")
                        .append(HtmlUtils.htmlEscape(line.text())).append("</td></tr>");
            }
        }
        return html.append("</tbody></table>").toString();
    }

    /** {@code action=rollback}（仿 MediaWiki）：回退目标用户对本页的连续编辑（管理员 / 行政员）。 */
    private Map<String, Object> rollback(Map<String, String> params, HttpServletRequest request, int fv) {
        WikiUser user = apiUser(request);
        requireCsrf(params, request);
        rejectBlocked(user);
        if (!permissionService.canManage(user.getId())) {
            throw new ApiError("permissiondenied", "You do not have permission to roll back edits.");
        }
        String title = normalizeTitle(Strings.trimToEmpty(params.get("title")));
        if (title.isEmpty()) {
            throw new ApiError("missingparam", "The title parameter must be set.");
        }
        WikiPage page = wikiPageService.getByTitle(title)
                .orElseThrow(() -> new ApiError("missingtitle", "The page you specified doesn't exist."));
        List<WikiRevision> revisions = wikiPageService.getHistory(page.getId());
        if (revisions.size() < 2) {
            throw new ApiError("alreadyrolled", "No previous revision to roll back to.");
        }
        WikiRevision latest = revisions.get(0);
        Long actor = parseLong(params.get("userid"));
        if (actor == null) {
            actor = latest.getUserId();
        }
        if (actor == null) {
            throw new ApiError("alreadyrolled", "The latest revision is anonymous; nothing to roll back.");
        }
        String targetName = Strings.trimToEmpty(params.get("user"));
        Long actorId = actor;
        if (!targetName.isEmpty()) {
            WikiUser target = userService.findByUsername(targetName)
                    .orElseThrow(() -> new ApiError("notanactor", "The user has not edited this page."));
            if (!Objects.equals(target.getId(), actorId)) {
                throw new ApiError("alreadyrolled", "The user has not made the latest edits to this page.");
            }
        }
        int index = 0;
        while (index < revisions.size() && Objects.equals(revisions.get(index).getUserId(), actorId)) {
            index++;
        }
        if (index <= 0 || index >= revisions.size()) {
            throw new ApiError("alreadyrolled", "The page has been edited by someone else; nothing to roll back.");
        }
        WikiRevision base = revisions.get(index);
        String actorName = userService.findById(actorId).map(WikiUser::getUsername).orElse("#" + actorId);
        String summary = Strings.trimToEmpty(params.get("summary"));
        if (summary.isEmpty()) {
            summary = message("rollback.summary", actorName, base.getId());
        }
        // 回滚是原样恢复旧版本，不能把旧版本里的字面量 ~~~~ 按当前操作者重签。
        wikiPageService.editPage(user, page.getTitle(), base.getContent(), summary, false, false, "", false);
        Map<String, Object> rollback = new LinkedHashMap<>();
        rollback.put("title", page.getTitle());
        rollback.put("summary", summary);
        rollback.put("revid", base.getId());
        rollback.put("actor", actorName);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rollback", rollback);
        return root;
    }

    // ==================== login / logout / tokens ====================

    private Map<String, Object> login(Map<String, String> params, HttpServletRequest request, int fv) {
        String lgname = Strings.trimToEmpty(params.get("lgname"));
        String lgpassword = Strings.trimToEmpty(params.get("lgpassword"));
        String token = Strings.trimToEmpty(params.get("lgtoken"));
        String loginToken = ActionApiTokens.login(request);
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> login = new LinkedHashMap<>();
        if (token.isEmpty()) {
            login.put("result", "NeedToken");
            login.put("token", loginToken);
            root.put("login", login);
            return root;
        }
        if (!ActionApiTokens.valid(request, loginToken, token)) {
            login.put("result", "WrongToken");
            login.put("token", loginToken);
            root.put("login", login);
            return root;
        }
        if (lgname.isEmpty() || lgpassword.isEmpty()) {
            throw new ApiError("missingparam", "The lgname and lgpassword parameters must be set.");
        }
        String clientKey = clientIpResolver.resolve(request) + "|" + lgname.toLowerCase(Locale.ROOT);
        RateLimiterService.Decision decision = rateLimiterService.check(RateLimiterService.LOGIN, clientKey);
        if (decision.limited()) {
            throw new ApiError("ratelimited",
                    message("rateLimit.login", decision.retryAfterSeconds()));
        }
        WikiUser user = userService.login(lgname, lgpassword);
        if (user == null) {
            rateLimiterService.failure(RateLimiterService.LOGIN, clientKey);
            login.put("result", "Failed");
            login.put("reason", "wrongpassword");
            login.put("message", message("auth.badCredentials"));
            root.put("login", login);
            return root;
        }
        rateLimiterService.success(RateLimiterService.LOGIN, clientKey);
        UserSession.login(request, user.toTransferCopy(), false);
        ActionApiTokens.rotateCsrf(request);
        login.put("result", "Success");
        login.put("lguserid", user.getId());
        login.put("lgusername", user.getUsername());
        root.put("login", login);
        return root;
    }

    private Map<String, Object> logout(HttpServletRequest request) {
        UserSession.logout(request);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> legacyTokens(Map<String, String> params, HttpServletRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tokens", tokens(request, params.get("type")));
        return root;
    }

    private Map<String, Object> tokens(HttpServletRequest request, String typeParam) {
        List<String> types = splitComma(typeParam);
        if (types.isEmpty()) {
            types = List.of("csrf");
        }
        Map<String, Object> tokens = new LinkedHashMap<>();
        for (String type : types) {
            switch (type) {
                case "csrf" -> tokens.put("csrftoken", ActionApiTokens.csrf(request));
                case "login" -> tokens.put("logintoken", ActionApiTokens.login(request));
                default -> tokens.put(type + "token", "+\\");
            }
        }
        return tokens;
    }

    // ==================== opensearch / help ====================

    private List<Object> opensearch(Map<String, String> params, HttpServletRequest request) {
        String search = Strings.trimToEmpty(params.get("search"));
        int limit = intParam(params.get("limit"), 10, 1, 100);
        WikiUser user = UserSession.current(request);
        List<String> titles = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        if (!search.isEmpty()) {
            for (SearchHit hit : searchService.search(search, limit)) {
                if (!accessControlService.canRead(hit.title(), user)) {
                    continue;
                }
                titles.add(hit.title());
                descriptions.add(Strings.trimToEmpty(hit.snippet()));
                urls.add(baseUrl(request) + "wiki/"
                        + java.net.URLEncoder.encode(hit.title(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"));
                if (titles.size() >= limit) {
                    break;
                }
            }
        }
        return List.of(search, titles, descriptions, urls);
    }

    /** 当前支持的 action 名（core + 插件）；供 ApiSandbox 与 help 共用。 */
    public List<String> actionNames() {
        List<String> actions = new ArrayList<>(List.of("query", "parse", "edit", "move", "delete", "protect",
                "block", "unblock", "upload", "compare", "rollback",
                "login", "logout", "opensearch", "tokens", "help"));
        for (ActionApiModule module : pluginManager.extensions(ActionApiModule.class)) {
            if (!actions.contains(module.action())) {
                actions.add(module.action());
            }
        }
        return List.copyOf(actions);
    }

    private Map<String, Object> help() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("actions", actionNames());
        help.put("formats", List.of("json", "jsonfm", "xml"));
        help.put("note", "MediaWiki Action API subset of WikiKernel.");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("help", help);
        return root;
    }

    // ==================== siteinfo / userinfo ====================

    private Map<String, Object> siteinfo(HttpServletRequest request) {
        Map<String, Object> general = new LinkedHashMap<>();
        general.put("mainpage", mainPage());
        general.put("base", baseUrl(request) + "wiki/" + urlEncode(mainPage()));
        general.put("sitename", siteNameService.name());
        general.put("generator", "WikiKernel");
        general.put("case", "first-letter");
        general.put("lang", locale().getLanguage());
        general.put("writeapi", Boolean.TRUE);
        general.put("readonly", "");
        return general;
    }

    private Map<String, Object> namespaces(boolean v2) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (v2) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ActionApiNamespaces.Ns ns : ActionApiNamespaces.all()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", ns.id());
                item.put("case", ActionApiNamespaces.caseOf(ns.id()));
                item.put("name", ActionApiNamespaces.displayName(ns.id(), locale()));
                list.add(item);
            }
            // v2 的 namespaces 仍是对象（键为编号），保持与 MediaWiki 一致
            for (Map<String, Object> item : list) {
                result.put(String.valueOf(item.get("id")), item);
            }
            return result;
        }
        for (ActionApiNamespaces.Ns ns : ActionApiNamespaces.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ns.id());
            item.put("case", ActionApiNamespaces.caseOf(ns.id()));
            item.put("*", ActionApiNamespaces.displayName(ns.id(), locale()));
            result.put(String.valueOf(ns.id()), item);
        }
        return result;
    }

    private Map<String, Object> userinfo(WikiUser user, HttpServletRequest request) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (user == null) {
            info.put("id", 0L);
            info.put("name", clientIpResolver.resolve(request));
            info.put("anon", Boolean.TRUE);
            info.put("groups", List.of("*"));
            info.put("rights", List.of("read", "writeapi"));
            return info;
        }
        info.put("id", user.getId());
        info.put("name", user.getUsername());
        List<String> groups = new ArrayList<>(permissionService.namesOf(user.getId()));
        if (!groups.contains(PermissionService.GROUP_USER)) {
            groups.add(0, PermissionService.GROUP_USER);
        }
        info.put("groups", groups);
        List<String> rights = new ArrayList<>(List.of("read", "edit", "createpage", "move", "writeapi"));
        if (permissionService.has(user.getId(), PermissionService.GROUP_BOT)) {
            rights.add("bot");
        }
        if (permissionService.canManage(user.getId())) {
            rights.add("delete");
            rights.add("protect");
        }
        info.put("rights", rights);
        return info;
    }

    // ==================== 工具 ====================

    private static List<String> splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\|")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> splitComma(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        // MediaWiki 的多值参数习惯用竖线（prop=ids|timestamp），也兼容逗号
        return List.of(value.split("[|,]")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<Integer> intList(String value) {
        List<Integer> result = new ArrayList<>();
        for (String item : splitPipe(value)) {
            for (String part : item.split(",")) {
                try {
                    result.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略非法值
                }
            }
        }
        return result;
    }

    private static Set<Long> parseIdSet(String value) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String item : splitPipe(value)) {
            Long id = parseLong(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static int intParam(String value, int defaultValue, int min, int max) {
        try {
            int parsed = Integer.parseInt(Strings.trimToEmpty(value));
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static Long parseLong(String value) {
        try {
            String text = Strings.trimToEmpty(value);
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** ISO（UTC / 偏移 / 本地）→ 服务器时区时间。 */
    private static LocalDateTime parseTime(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return TransferTimes.fromUtcIso(text);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String normalizeTitle(String value) {
        return WikiTitles.normalize(value);
    }

    private static String timestamp(LocalDateTime time) {
        return time == null ? "" : TransferTimes.toUtcIso(time);
    }

    private static Locale locale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, locale());
    }

    private String mainPage() {
        return message("mainPage");
    }

    private String anonName() {
        return message("common.anonymous");
    }

    private Map<Long, Long> parentIds(List<WikiRevision> historyDesc) {
        Map<Long, Long> parents = new LinkedHashMap<>();
        Long previous = null;
        for (int i = historyDesc.size() - 1; i >= 0; i--) {
            WikiRevision revision = historyDesc.get(i);
            if (previous != null) {
                parents.put(revision.getId(), previous);
            }
            previous = revision.getId();
        }
        return parents;
    }

    private Long parentId(WikiRevision revision) {
        WikiPage page = wikiPageMapper.selectById(revision.getPageId());
        if (page == null) {
            return null;
        }
        return wikiPageService.getPreviousRevision(page.getId(), revision.getId())
                .map(WikiRevision::getId)
                .orElse(null);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String baseUrl(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return request.getScheme() + "://" + request.getServerName()
                + (defaultPort ? "" : ":" + port) + "/";
    }

    /** imageinfo 需要的请求上下文（由控制器注入到 ThreadLocal 太绕，这里用当前请求属性兜底）。 */
    private HttpServletRequest requestOfPage() {
        return RequestContextHolder.current();
    }

    /** 简易请求上下文（控制器在调用 API 前设置，便于深层方法拿到 base URL）。 */
    public static final class RequestContextHolder {
        private static final ThreadLocal<HttpServletRequest> CURRENT = new ThreadLocal<>();

        private RequestContextHolder() {
        }

        public static void set(HttpServletRequest request) {
            CURRENT.set(request);
        }

        public static HttpServletRequest current() {
            return CURRENT.get();
        }

        public static void clear() {
            CURRENT.remove();
        }
    }
}
