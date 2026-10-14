package cc.thonly.wikikernel.plugin.aiagent;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.service.CategoryCounts;
import cc.thonly.wikikernel.common.service.CategoryMember;
import cc.thonly.wikikernel.common.service.CategoryStore;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.EditSubmitter;
import cc.thonly.wikikernel.common.service.PageLinkStore;
import cc.thonly.wikikernel.common.service.PageQueryStore;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.SearchStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.aiagent.service.AiAgentService;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;
import cc.thonly.wikikernel.plugin.aiagent.service.MarkdownRenderer;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import tools.jackson.databind.ObjectMapper;

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
 * AI 代理插件的测试替身：把 core 的桥（{@code common.service.*}）换成内存实现，
 * 于是「会话编排 / 工具 / 提案 / 确认提交 / 密钥优先级」都能在**不联网、不启动 core**的情况下验证。
 * <p>
 * 关键约定：
 * <ul>
 *     <li>{@link FakeEditSubmitter} 记录每一次提交（含 {@code aiAssisted} / {@code source} / 基准版本），
 *     并按真实桥的语义改页面 / 版本 —— 用来断言「source=ai 提交走 EditSubmitter」；</li>
 *     <li>{@link FakeAiConfig} 同时充当用户 / 站点 / yaml 三处来源，用来验证优先级；</li>
 *     <li>厂商一律用插件内置的 {@code mock} 适配器（离线剧本），不发任何网络请求。</li>
 * </ul>
 */
public final class AiAgentFakes {

    private AiAgentFakes() {
    }

    /** 一次测试用到的全部替身 + 装配好的服务 / 注册表 / 支持对象。 */
    public static final class Fixture {

        public final FakeAiConfig aiConfig = new FakeAiConfig();
        public final FakeSiteConfig siteConfig = new FakeSiteConfig();
        public final FakeAccessCheck accessCheck = new FakeAccessCheck();
        public final FakePageQueryStore pageQuery = new FakePageQueryStore();
        public final FakeWikiPageStore pageStore = new FakeWikiPageStore();
        public final FakePageLinkStore pageLinks = new FakePageLinkStore();
        public final FakeCategoryStore categories = new FakeCategoryStore();
        public final FakeSearchStore search = new FakeSearchStore();
        public final FakeUserStore users = new FakeUserStore();
        public final FakeWikiDiff wikiDiff = new FakeWikiDiff();
        public final FakeWikiText wikiText = new FakeWikiText();
        public final FakeEditSubmitter editSubmitter = new FakeEditSubmitter(pageQuery, pageStore);
        public final FakeRateLimit rateLimit = new FakeRateLimit();
        public final TestMessageSource messages = TestMessageSource.of(new AiAgentMessages());

        public final AiAgentSupport support;
        public final AiProviderRegistry registry;
        public final AiAgentService service;

        public Fixture() {
            support = new AiAgentSupport(siteConfig, messages);
            registry = new AiProviderRegistry(aiConfig, new ObjectMapper(), support);
            service = new AiAgentService(registry, aiConfig, support, accessCheck, pageQuery, pageStore,
                    pageLinks, categories, search, users, wikiText, wikiDiff, editSubmitter,
                    new MarkdownRenderer(), new ObjectMapper());
            aiConfig.settings = new AiConfig.Settings("mock", false, 0, 180, "", 4000, 120, 200, 60);
            siteConfig.values.put(AiAgentSupport.ENABLED_KEY, "true");
        }

        // ==================== 便捷方法 ====================

        public Viewer user(String name, String... groups) {
            long id = pageQuery.nextUserId();
            users.add(id, name, groups);
            return new Viewer(id, name, false, new LinkedHashSet<>(List.of(groups)), false);
        }

        public Viewer agent(String name) {
            return user(name, AiAgentSupport.GROUP_AI_AGENT);
        }

        public Viewer admin(String name) {
            return user(name, AiAgentSupport.GROUP_ADMIN);
        }

        public void page(String title, String content) {
            page(title, content, 1L);
        }

        public void page(String title, String content, Long authorId) {
            pageQuery.put(title, content, authorId);
            pageStore.put(pageQuery.idOf(title), content);
        }

        public String content(String title) {
            return pageStore.content(pageQuery.idOf(title)).orElse("");
        }

        public void enabled(boolean value) {
            siteConfig.values.put(AiAgentSupport.ENABLED_KEY, Boolean.toString(value));
        }

        public void allowNormalUsers(boolean value) {
            aiConfig.allowNormalUsers = value;
        }
    }

    /**
     * 插件文案的 {@code MessageSource} 替身：按 core 的规则解析（语言回退 {@code zh-CN → zh → en}），
     * 于是测试拿到的是**真实文案**（含格式化参数），不是键名。
     */
    public static final class TestMessageSource implements MessageSource {

        private final Map<String, Map<String, String>> byLanguage;
        private final Map<String, String> coreKeys = Map.of(
                "permissions.loginRequired", "请先登录。",
                "format.dateTime", "yyyy-MM-dd HH:mm");

        private TestMessageSource(Map<String, Map<String, String>> byLanguage) {
            this.byLanguage = byLanguage;
        }

        public static TestMessageSource of(MessageBundle bundle) {
            return new TestMessageSource(bundle.messages());
        }

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            String text = resolve(code, locale);
            return text == null ? defaultMessage : format(text, args);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            String text = resolve(code, locale);
            if (text == null) {
                throw new NoSuchMessageException(code, locale);
            }
            return format(text, args);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            String[] codes = resolvable.getCodes();
            return getMessage(codes == null || codes.length == 0 ? "" : codes[0],
                    resolvable.getArguments(), resolvable.getDefaultMessage(), locale);
        }

        private String resolve(String code, Locale locale) {
            String language = locale == null ? "" : locale.getLanguage();
            String text = byLanguage.getOrDefault(language, Map.of()).get(code);
            if (text == null) {
                text = byLanguage.getOrDefault("en", Map.of()).get(code);
            }
            return text == null ? coreKeys.get(code) : text;
        }

        private static String format(String text, Object[] args) {
            return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
        }
    }

    // ==================== AI 厂商配置（AiConfig 桥替身） ====================

    public static final class FakeAiConfig implements AiConfig {

        public final Map<String, Entry> userEntries = new LinkedHashMap<>();
        public final Map<String, Entry> siteEntries = new LinkedHashMap<>();
        public final Map<String, Entry> yamlEntries = new LinkedHashMap<>();
        public Settings settings = new Settings("", false, 0, 180, "", 24000, 120, 200, 60);
        public boolean allowNormalUsers;
        public final List<String> siteSaves = new ArrayList<>();
        public final List<String> userSaves = new ArrayList<>();

        @Override
        public Settings settings() {
            return new Settings(settings.defaultProvider(), allowNormalUsers, settings.maxSteps(),
                    settings.timeoutSeconds(), settings.proxy(), settings.maxReadChars(),
                    settings.sessionTtlMinutes(), settings.maxSessions(), settings.maxMessages());
        }

        @Override
        public Entry user(long userId, String providerId) {
            return userEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", ""));
        }

        @Override
        public Entry site(String providerId) {
            return siteEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", ""));
        }

        @Override
        public Entry yaml(String providerId) {
            return yamlEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", ""));
        }

        @Override
        public boolean yamlHasKey(String providerId) {
            return yaml(providerId).hasKey();
        }

        @Override
        public Map<String, Entry> yamlProviders() {
            return Map.copyOf(yamlEntries);
        }

        @Override
        public void saveUser(long userId, String providerId, String apiKey, String baseUrl, String model,
                             String proxy, boolean clear) {
            userSaves.add(providerId + ":" + (clear ? "clear" : apiKey));
            if (clear) {
                userEntries.remove(providerId);
                return;
            }
            Entry current = userEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", ""));
            userEntries.put(providerId, new Entry(providerId, apiKey == null ? current.apiKey() : apiKey,
                    baseUrl == null ? current.baseUrl() : baseUrl,
                    model == null ? current.model() : model,
                    proxy == null ? current.proxy() : proxy));
        }

        @Override
        public void saveSite(String providerId, String apiKey, String baseUrl, String model, String proxy,
                             boolean clear) {
            siteSaves.add(providerId + ":" + (clear ? "clear" : apiKey));
            if (clear) {
                siteEntries.remove(providerId);
                return;
            }
            Entry current = siteEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", ""));
            siteEntries.put(providerId, new Entry(providerId, apiKey == null ? current.apiKey() : apiKey,
                    baseUrl == null ? current.baseUrl() : baseUrl,
                    model == null ? current.model() : model,
                    proxy == null ? current.proxy() : proxy));
        }

        @Override
        public void saveAllowNormalUsers(boolean allow) {
            this.allowNormalUsers = allow;
        }

        @Override
        public String sourceOf(long userId, String providerId) {
            if (userEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", "")).configured()) {
                return "user";
            }
            if (siteEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", "")).configured()) {
                return "site";
            }
            if (yamlEntries.getOrDefault(providerId, new Entry(providerId, "", "", "", "")).configured()) {
                return "yaml";
            }
            return "none";
        }
    }

    // ==================== 站点设置 ====================

    public static final class FakeSiteConfig implements SiteConfig {

        public final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            if (value == null) {
                return defaultValue;
            }
            return "true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value == null ? "" : value);
        }
    }

    // ==================== 权限 ====================

    public static final class FakeAccessCheck implements AccessCheck {

        /** 不允许的动作（{@code action + "|" + target}）；空 = 全允许。 */
        public final Set<String> forbidden = new LinkedHashSet<>();

        public void deny(AccessAction action, String target) {
            forbidden.add(action.id() + "|" + target);
        }

        @Override
        public boolean can(AccessAction action, String target, Viewer viewer) {
            if (viewer == null || !viewer.loggedIn()) {
                return !forbidden.contains(action.id() + "|" + target);
            }
            return !forbidden.contains(action.id() + "|" + target);
        }

        @Override
        public Optional<String> blockNotice(Viewer viewer) {
            return Optional.empty();
        }
    }

    // ==================== 页面 / 版本 ====================

    public static final class FakePageQueryStore implements PageQueryStore {

        public final Map<String, PageDetail> pages = new LinkedHashMap<>();
        public final Map<Long, List<RevisionEntry>> revisions = new LinkedHashMap<>();
        private final AtomicLong pageIds = new AtomicLong(100);
        private final AtomicLong revisionIds = new AtomicLong(1000);
        private final AtomicLong userIds = new AtomicLong(1);

        public long nextUserId() {
            return userIds.incrementAndGet();
        }

        public long idOf(String title) {
            return pages.getOrDefault(title, new PageDetail(0, title, null, false, null, null)).id();
        }

        /** 该标题当前的最新版本 id（{@code null} = 页面不存在，用于模拟乐观锁）。 */
        public Long currentRevision(String title) {
            PageDetail page = pages.get(title);
            return page == null ? null : page.latestRevisionId();
        }

        public void put(String title, String content) {
            put(title, content, 1L);
        }

        public void put(String title, String content, Long authorId) {
            PageDetail existing = pages.get(title);
            long pageId = existing == null ? pageIds.incrementAndGet() : existing.id();
            long revisionId = revisionIds.incrementAndGet();
            pages.put(title, new PageDetail(pageId, title, revisionId, false,
                    LocalDateTime.now().minusDays(1), LocalDateTime.now()));
            revisions.computeIfAbsent(pageId, key -> new ArrayList<>())
                    .add(0, new RevisionEntry(revisionId, authorId, "测试版本", content.length(), false, false,
                            false, "", false, LocalDateTime.now()));
        }

        @Override
        public List<String> namespaces() {
            return List.of("main", "template", "module", "widget", "gadget", "category", "help", "user", "file",
                    "wikikernel");
        }

        @Override
        public Optional<PageDetail> detail(String title) {
            return Optional.ofNullable(pages.get(title));
        }

        @Override
        public List<PageEntry> list(String prefix, String namespace, int limit) {
            String wanted = namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
            String titlePrefix = prefix == null ? "" : prefix.trim();
            List<PageEntry> rows = new ArrayList<>();
            for (PageDetail page : pages.values()) {
                if (!titlePrefix.isEmpty() && !page.title().startsWith(titlePrefix)) {
                    continue;
                }
                boolean namespaced = page.title().contains(":");
                if ("main".equals(wanted) && namespaced) {
                    continue;
                }
                if (("template".equals(wanted) && !page.title().startsWith("模板:"))
                        || ("module".equals(wanted) && !page.title().startsWith("模块:"))) {
                    continue;
                }
                rows.add(new PageEntry(page.id(), page.title(), page.latestRevisionId(), page.updatedAt()));
            }
            rows.sort(Comparator.comparing(PageEntry::title));
            int max = limit <= 0 ? rows.size() : Math.min(limit, rows.size());
            return List.copyOf(rows.subList(0, max));
        }

        @Override
        public Optional<RevisionEntry> latestRevision(long pageId) {
            return revisions.getOrDefault(pageId, List.of()).stream().findFirst();
        }

        @Override
        public List<RevisionEntry> history(long pageId, int limit) {
            List<RevisionEntry> rows = revisions.getOrDefault(pageId, List.of());
            return List.copyOf(limit > 0 ? rows.subList(0, Math.min(limit, rows.size())) : rows);
        }
    }

    public static final class FakeWikiPageStore implements WikiPageStore {

        public final Map<Long, String> contents = new LinkedHashMap<>();

        public void put(long pageId, String content) {
            contents.put(pageId, content);
        }

        @Override
        public Optional<PageSnapshot> byId(long pageId) {
            return Optional.empty();
        }

        @Override
        public Optional<PageSnapshot> byTitle(String title) {
            return Optional.empty();
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.ofNullable(contents.get(pageId));
        }

        @Override
        public List<PageSnapshot> contentPages() {
            return List.of();
        }
    }

    // ==================== 链接 / 分类 / 搜索 / 用户 / 渲染 / 差异 ====================

    public static final class FakePageLinkStore implements PageLinkStore {

        public final Map<String, List<String>> templates = new LinkedHashMap<>();
        public final Map<String, List<String>> linksHere = new LinkedHashMap<>();
        public final Map<String, List<String>> transcludedBy = new LinkedHashMap<>();

        @Override
        public List<String> templatesUsedBy(String pageTitle) {
            return templates.getOrDefault(pageTitle, List.of());
        }

        @Override
        public List<String> pagesLinkingTo(String title, int limit) {
            return cut(linksHere.getOrDefault(title, List.of()), limit);
        }

        @Override
        public List<String> pagesTranscluding(String templateTitle, int limit) {
            return cut(transcludedBy.getOrDefault(templateTitle, List.of()), limit);
        }

        private static List<String> cut(List<String> values, int limit) {
            return limit > 0 ? List.copyOf(values.subList(0, Math.min(limit, values.size()))) : List.copyOf(values);
        }
    }

    public static final class FakeCategoryStore implements CategoryStore {

        public final Map<Long, List<String>> byPage = new LinkedHashMap<>();

        @Override
        public List<CategoryMember> members(String categoryTitle, int limit) {
            return List.of();
        }

        @Override
        public List<String> categoriesOf(long pageId) {
            return byPage.getOrDefault(pageId, List.of());
        }

        @Override
        public CategoryCounts counts(String categoryTitle) {
            return new CategoryCounts(0, 0, 0, 0);
        }
    }

    public static final class FakeSearchStore implements SearchStore {

        public final List<SearchHit> hits = new ArrayList<>();

        @Override
        public List<SearchHit> search(String keyword, int limit) {
            return hits.stream()
                    .filter(hit -> hit.title().contains(keyword) || hit.snippet().contains(keyword))
                    .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                    .toList();
        }
    }

    public static final class FakeUserStore implements UserStore {

        public final Map<Long, String> names = new LinkedHashMap<>();
        public final Map<Long, Set<String>> groups = new LinkedHashMap<>();

        public void add(long id, String name, String... groupNames) {
            names.put(id, name);
            groups.put(id, new LinkedHashSet<>(List.of(groupNames)));
        }

        @Override
        public Optional<UserRef> byId(long userId) {
            return Optional.ofNullable(names.get(userId))
                    .map(name -> new UserRef(userId, name, "", false));
        }

        @Override
        public Optional<UserRef> byName(String username) {
            return names.entrySet().stream().filter(entry -> entry.getValue().equals(username))
                    .map(entry -> new UserRef(entry.getKey(), entry.getValue(), "", false)).findFirst();
        }

        @Override
        public Map<Long, String> names(Collection<Long> userIds) {
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : userIds) {
                if (names.containsKey(id)) {
                    result.put(id, names.get(id));
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
            // AI 代理用不到「随机用户」（social-profile 插件的特殊:随机用户才用）；
            // 这里按接口语义返回空列表（没有上传过头像的用户）
            return List.of();
        }
    }

    public static final class FakeWikiText implements WikiText {

        @Override
        public String toHtml(String wikitext, String pageTitle) {
            return "<div class=\"preview\" data-title=\"" + pageTitle + "\">" + wikitext + "</div>";
        }
    }

    public static final class FakeWikiDiff implements WikiDiff {

        @Override
        public List<Map<String, Object>> rows(String oldContent, String newContent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("oldMarker", "−");
            row.put("oldNo", "1");
            row.put("oldClass", "diff-deletedline");
            row.put("oldBefore", "");
            row.put("oldChange", oldContent);
            row.put("oldAfter", "");
            row.put("newMarker", "+");
            row.put("newNo", "1");
            row.put("newClass", "diff-addedline");
            row.put("newBefore", "");
            row.put("newChange", newContent);
            row.put("newAfter", "");
            return List.of(row);
        }
    }

    // ==================== 提交（EditSubmitter 桥替身） ====================

    public static final class FakeEditSubmitter implements EditSubmitter {

        public final List<Request> requests = new ArrayList<>();
        /** 配置成失败的语义（{@code blocked} / {@code handled} / {@code conflict} / {@code saveFailed}）。 */
        public String failure;
        public String failureMessage = "";
        private final FakePageQueryStore pages;
        private final FakeWikiPageStore contents;

        public FakeEditSubmitter(FakePageQueryStore pages, FakeWikiPageStore contents) {
            this.pages = pages;
            this.contents = contents;
        }

        @Override
        public Result submit(Request request) {
            requests.add(request);
            if (failure != null) {
                return "handled".equals(failure) ? Result.handled(failureMessage)
                        : Result.failure(failure, failureMessage);
            }
            if (request.baseRevisionId() != null
                    && !request.baseRevisionId().equals(pages.currentRevision(request.title()))) {
                return Result.failure("conflict", "");
            }
            pages.put(request.title(), request.content(), request.actorUserId());
            long pageId = pages.idOf(request.title());
            contents.put(pageId, request.content());
            Long revisionId = pages.currentRevision(request.title());
            return Result.ok(pageId, revisionId, List.of());
        }
    }

    // ==================== 当前用户（控制器用） ====================

    public static final class FakeCurrentUser implements CurrentUser {

        public Viewer viewer = Viewer.ANONYMOUS;

        @Override
        public Viewer viewer() {
            return viewer;
        }
    }

    // ==================== 限流 ====================

    public static final class FakeRateLimit implements RateLimit {

        public boolean limited;
        public long retryAfterSeconds = 30;
        public int hits;

        @Override
        public Decision check(String category, String key) {
            return limited ? Decision.blocked(retryAfterSeconds) : Decision.allowed();
        }

        @Override
        public void hit(String category, String key) {
            hits++;
        }

        @Override
        public void success(String category, String key) {
        }
    }
}
