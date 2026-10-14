package cc.thonly.wikikernel.plugin.moderation;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.FileWriter;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.PageWriter;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationActionGuard;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationEditFormField;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationEditGuard;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationPageDisplayHook;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationServiceImpl;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationStore;
import cc.thonly.wikikernel.plugin.moderation.service.PendingFileStore;
import cc.thonly.wikikernel.plugin.moderation.web.ModerationSpecialPage;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.text.MessageFormat;
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
 * 测试替身：内存版的审核队列 / 待审文件 / 页面写入 / 文件写入 / 差异 / 日志桥，以及
 * 站点设置、文案源、当前用户与各类钩子上下文。
 * 全部不依赖 Spring 与 core（插件测试只跑插件自己的代码）。
 */
public final class ModerationFakes {

    private ModerationFakes() {
    }

    // ==================== 站点设置与文案 ====================

    /** 功能开关替身：只认 {@code features.moderation}。 */
    public static SiteConfig config(boolean enabled) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return ModerationSupport.ENABLED_KEY.equals(key) ? enabled : defaultValue;
            }
        };
    }

    /** 文案源替身：返回兜底文案并按 {@link MessageFormat} 填参（与 core 的 MessageSource 行为一致）。 */
    public static MessageSource messages() {
        return messages(Map.of());
    }

    /** 文案源替身 + 少量「插件消息包」里的文案（验证消息键解析）。 */
    public static MessageSource messages(Map<String, String> bundle) {
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                String text = bundle.getOrDefault(code, defaultMessage == null ? code : defaultMessage);
                return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return bundle.getOrDefault(code, code);
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable,
                                     Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    /**
     * 文案源替身：直接用插件自己的中文消息包（core 在生产环境里也是把插件消息包并进 messageSource），
     * 因此断言可以写真实文案，同时顺带验证键名与参数格式。
     */
    public static MessageSource pluginMessages() {
        return messages(new ModerationMessages().messages().get("zh"));
    }

    // ==================== 用户 / 页面 ====================

    public static UserStore userStore(Map<Long, String> names, Map<Long, Set<String>> groups) {
        return new UserStore() {
            @Override
            public Optional<UserRef> byId(long userId) {
                String name = names.get(userId);
                return name == null ? Optional.empty() : Optional.of(new UserRef(userId, name, "", true));
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

    /** 差异桥替身：按行给出「未变 / 删除 / 新增」三类行（字段与 core 的行模型一致）。 */
    public static WikiDiff wikiDiff() {
        return (oldContent, newContent) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            String[] oldLines = Strings.trimToEmpty(oldContent).split("\n", -1);
            String[] newLines = Strings.trimToEmpty(newContent).split("\n", -1);
            int count = Math.max(oldLines.length, newLines.length);
            for (int i = 0; i < count; i++) {
                String oldLine = i < oldLines.length ? oldLines[i] : null;
                String newLine = i < newLines.length ? newLines[i] : null;
                Map<String, Object> row = new LinkedHashMap<>();
                boolean same = oldLine != null && oldLine.equals(newLine);
                row.put("oldNo", oldLine == null ? "" : i + 1);
                row.put("oldMarker", same || oldLine == null ? "" : "-");
                row.put("oldClass", oldLine == null ? "diff-line diff-empty"
                        : same ? "diff-line" : "diff-line diff-deletedline");
                row.put("oldBefore", oldLine == null ? "\u00a0" : "");
                row.put("oldChange", oldLine == null ? "" : oldLine);
                row.put("oldAfter", "");
                row.put("newNo", newLine == null ? "" : i + 1);
                row.put("newMarker", same || newLine == null ? "" : "+");
                row.put("newClass", newLine == null ? "diff-line diff-empty"
                        : same ? "diff-line" : "diff-line diff-addedline");
                row.put("newBefore", newLine == null ? "\u00a0" : "");
                row.put("newChange", newLine == null ? "" : newLine);
                row.put("newAfter", "");
                rows.add(row);
            }
            return rows;
        };
    }

    /** wikitext 渲染桥替身：把正文包一层（只验证「预览走 WikiText」）。 */
    public static WikiText wikiText() {
        return (wikitext, pageTitle) -> "<div class=\"rendered\">" + Strings.trimToEmpty(wikitext) + "</div>";
    }

    // ==================== 数据桥 ====================

    /** 内存版待审队列。 */
    public static final class InMemoryModerationStore implements ModerationStore {

        public final Map<Long, WikiPendingChange> changes = new LinkedHashMap<>();
        public final AtomicLong ids = new AtomicLong();

        @Override
        public WikiPendingChange insert(WikiPendingChange change) {
            change.setId(ids.incrementAndGet());
            changes.put(change.getId(), change);
            return change;
        }

        @Override
        public void update(WikiPendingChange change) {
            changes.put(change.getId(), change);
        }

        @Override
        public Optional<WikiPendingChange> find(long id) {
            return Optional.ofNullable(changes.get(id));
        }

        @Override
        public List<WikiPendingChange> list(String status, String type, String titleLike, int limit) {
            return changes.values().stream()
                    .filter(change -> matches(change.getStatus(), status))
                    .filter(change -> matches(change.getChangeType(), type))
                    .filter(change -> Strings.isBlank(titleLike)
                            || Strings.trimToEmpty(change.getTitle()).contains(titleLike.trim()))
                    .sorted(Comparator.comparing(WikiPendingChange::getId).reversed())
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .toList();
        }

        @Override
        public List<WikiPendingChange> forPage(String title) {
            return changes.values().stream()
                    .filter(change -> WikiPendingChange.STATUS_PENDING.equals(change.getStatus()))
                    .filter(change -> Strings.trimToEmpty(title).equals(change.getTitle()))
                    .sorted(Comparator.comparing(WikiPendingChange::getId).reversed())
                    .toList();
        }

        @Override
        public long count(String status) {
            return changes.values().stream().filter(change -> matches(change.getStatus(), status)).count();
        }

        @Override
        public long countForPage(String title, String status) {
            return changes.values().stream()
                    .filter(change -> Strings.trimToEmpty(title).equals(change.getTitle()))
                    .filter(change -> matches(change.getStatus(), status))
                    .count();
        }

        @Override
        public List<WikiPendingChange> byUser(long userId, int limit) {
            return changes.values().stream()
                    .filter(change -> change.getUserId() != null && change.getUserId() == userId)
                    .sorted(Comparator.comparing(WikiPendingChange::getId).reversed())
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .toList();
        }

        private static boolean matches(String value, String filter) {
            return Strings.isBlank(filter) || Strings.trimToEmpty(filter).equals(value);
        }
    }

    /** 内存版待审文件暂存（插件数据目录 {@code pending/} 的替身）。 */
    public static final class InMemoryPendingFiles implements PendingFileStore {

        public final Map<String, byte[]> files = new LinkedHashMap<>();
        public final AtomicLong ids = new AtomicLong();

        @Override
        public String stage(String fileName, byte[] content) {
            String stored = "pending-" + ids.incrementAndGet() + extensionOf(fileName);
            files.put(stored, content == null ? new byte[0] : content);
            return stored;
        }

        @Override
        public Optional<byte[]> read(String storedName) {
            byte[] content = files.get(Strings.trimToEmpty(storedName));
            return content == null ? Optional.empty() : Optional.of(content.clone());
        }

        @Override
        public boolean delete(String storedName) {
            return files.remove(Strings.trimToEmpty(storedName)) != null;
        }

        private static String extensionOf(String fileName) {
            String name = Strings.trimToEmpty(fileName);
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(dot);
        }
    }

    /** 页面写入桥替身：记录请求，模拟「用户不存在 / 版本冲突 / 保存成功」。 */
    public static final class FakePageWriter implements PageWriter {

        public final List<Request> requests = new ArrayList<>();
        public final Set<Long> missingUsers = new LinkedHashSet<>();
        public final Map<String, Long> pageIds = new LinkedHashMap<>();
        public final AtomicLong revisionIds = new AtomicLong();
        public final List<String> deleted = new ArrayList<>();

        @Override
        public Result save(Request request) {
            requests.add(request);
            if (missingUsers.contains(request.actorUserId())) {
                return Result.failure("userMissing");
            }
            long pageId = pageIds.computeIfAbsent(request.title(), title -> (long) (pageIds.size() + 1));
            return Result.ok(pageId, revisionIds.incrementAndGet());
        }

        @Override
        public Result delete(long actorUserId, String title, String summary) {
            deleted.add(title);
            return Result.ok(1L, 0L);
        }
    }

    /** 文件写入桥替身：记录写入 / 删除。 */
    public static final class FakeFileWriter implements FileWriter {

        public final List<String> stored = new ArrayList<>();
        public final List<String> deleted = new ArrayList<>();
        public final Map<String, byte[]> contents = new LinkedHashMap<>();
        public final Set<Long> missingUsers = new LinkedHashSet<>();
        public boolean failStore;

        @Override
        public Result store(long actorUserId, String name, String description, byte[] content) {
            if (missingUsers.contains(actorUserId)) {
                return Result.failed("userMissing");
            }
            if (failStore) {
                return Result.failed("files.tooLarge");
            }
            stored.add(name);
            contents.put(name, content == null ? new byte[0] : content.clone());
            return Result.ok(stored.size(), name, content == null ? 0 : content.length, "image/png");
        }

        @Override
        public boolean delete(long actorUserId, long fileId, boolean hard) {
            if (missingUsers.contains(actorUserId)) {
                return false;
            }
            deleted.add(actorUserId + ":" + fileId + "|" + hard);
            return true;
        }
    }

    /** 日志写入桥替身。 */
    public static final class FakeLogWriter implements LogWriter {

        public final List<String> logs = new ArrayList<>();

        @Override
        public void record(String logType, Long actorUserId, String target, Long targetUserId, String detail) {
            logs.add(logType + "|" + actorUserId + "|" + target + "|" + targetUserId + "|" + detail);
        }
    }

    // ==================== 钩子上下文替身 ====================

    /** {@link EditContext} 替身（保存前钩子用）。 */
    public static final class FakeEditContext implements EditContext {

        public Viewer viewer = Viewer.ANONYMOUS;
        public String title = "测试页";
        public String content = "正文";
        public String summary = "摘要";
        public boolean minor;
        public String action = ACTION_EDIT;
        public Long baseRevisionId;
        public Map<String, String> parameters = Map.of();
        public String source = SOURCE_WEB;

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public boolean newPage() {
            return ACTION_CREATE.equals(action);
        }

        @Override
        public String content() {
            return content;
        }

        @Override
        public String summary() {
            return summary;
        }

        @Override
        public boolean minor() {
            return minor;
        }

        @Override
        public String action() {
            return action;
        }

        @Override
        public Long baseRevisionId() {
            return baseRevisionId;
        }

        @Override
        public Map<String, String> parameters() {
            return parameters;
        }

        @Override
        public SessionAttributes session() {
            return SessionAttributes.NONE;
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public String userAgent() {
            return "test";
        }

        @Override
        public String source() {
            return source;
        }
    }

    /** {@link EditFormContext} 替身（编辑页提示用）。 */
    public static final class FakeEditFormContext implements EditFormContext {

        public Viewer viewer = Viewer.ANONYMOUS;
        public String title = "测试页";

        @Override
        public String title() {
            return title;
        }

        @Override
        public boolean newPage() {
            return false;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public SessionAttributes session() {
            return SessionAttributes.NONE;
        }

        @Override
        public Map<String, String> parameters() {
            return Map.of();
        }

        @Override
        public boolean preview() {
            return false;
        }
    }

    /** {@link ActionContext} 替身（上传 / 删除用）。 */
    public static final class FakeActionContext implements ActionContext {

        public Viewer viewer = Viewer.ANONYMOUS;
        public String action = "upload";
        public String target = "文件:甲.png";
        public String summary = "";
        public Map<String, String> data = new LinkedHashMap<>();
        public Map<String, String> parameters = Map.of();
        public byte[] payload;
        public String source = SOURCE_WEB;

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String action() {
            return action;
        }

        @Override
        public String target() {
            return target;
        }

        @Override
        public String summary() {
            return summary;
        }

        @Override
        public Map<String, String> data() {
            return Map.copyOf(data);
        }

        @Override
        public Map<String, String> parameters() {
            return parameters;
        }

        @Override
        public SessionAttributes session() {
            return SessionAttributes.NONE;
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public String userAgent() {
            return "test";
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }

    /** {@link PageDisplayContext} 替身（提示条 / 待审创建页用）。 */
    public static final class FakePageDisplayContext implements PageDisplayContext {

        public long pageId;
        public String title = "测试页";
        public String content = "";
        public boolean oldRevision;
        public Map<String, String> query = Map.of();
        public Viewer viewer = Viewer.ANONYMOUS;

        @Override
        public long pageId() {
            return pageId;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String content() {
            return content;
        }

        @Override
        public boolean oldRevision() {
            return oldRevision;
        }

        @Override
        public String pageUrl() {
            return ModerationSupport.pagePath(title);
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public Map<String, String> query() {
            return query;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }

        @Override
        public String siteName() {
            return "测试站";
        }

        @Override
        public String baseUrl() {
            return "http://localhost:8080";
        }
    }

    /** {@link HeadContext} 替身（前端资源注入用）。 */
    public static final class FakeHeadContext implements HeadContext {

        @Override
        public long pageId() {
            return 0;
        }

        @Override
        public String pageTitle() {
            return "特殊:审核";
        }

        @Override
        public String path() {
            return "/wiki/特殊:审核";
        }

        @Override
        public Map<String, String> query() {
            return Map.of();
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public String siteName() {
            return "测试站";
        }

        @Override
        public String baseUrl() {
            return "http://localhost:8080";
        }

        @Override
        public String fileUrl(String fileName) {
            return "";
        }

        @Override
        public int fileWidth(String fileName) {
            return 0;
        }

        @Override
        public int fileHeight(String fileName) {
            return 0;
        }
    }

    /** 当前请求用户桥替身。 */
    public static CurrentUser currentUser(Viewer viewer) {
        return () -> viewer;
    }

    // ==================== 组合 ====================

    /** 一个插件支撑 + 服务 + 钩子的组合（各测试按需改 fake 里的状态）。 */
    public static final class Fixture {

        public final InMemoryModerationStore store = new InMemoryModerationStore();
        public final InMemoryPendingFiles pendingFiles = new InMemoryPendingFiles();
        public final FakePageWriter pageWriter = new FakePageWriter();
        public final FakeFileWriter fileWriter = new FakeFileWriter();
        public final FakeLogWriter logWriter = new FakeLogWriter();
        public final Map<Long, String> names = new LinkedHashMap<>();
        public final Map<Long, Set<String>> groups = new LinkedHashMap<>();
        public final Map<String, Long> pageIds = new LinkedHashMap<>();
        public final Map<Long, String> pageContents = new LinkedHashMap<>();
        public boolean enabled = true;

        private ModerationSupport support;
        private ModerationService service;

        /** 支撑（读 {@code enabled} 字段的实时值，测试里可以随时切换开关）。 */
        public ModerationSupport support() {
            if (support == null) {
                SiteConfig config = new SiteConfig() {
                    @Override
                    public String get(String key, String defaultValue) {
                        return defaultValue;
                    }

                    @Override
                    public boolean getBoolean(String key, boolean defaultValue) {
                        return ModerationSupport.ENABLED_KEY.equals(key) ? enabled : defaultValue;
                    }
                };
                support = new ModerationSupport(config, pluginMessages(), pageStore());
            }
            return support;
        }

        /** 服务（同一 fixture 只建一次）。 */
        public ModerationService service() {
            if (service == null) {
                service = new ModerationServiceImpl(store, pendingFiles, pageWriter, fileWriter,
                        pageStore(), logWriter, support());
            }
            return service;
        }

        public UserStore userStore() {
            return ModerationFakes.userStore(names, groups);
        }

        public WikiPageStore pageStore() {
            return ModerationFakes.pageStore(pageIds, pageContents);
        }

        public WikiDiff wikiDiff() {
            return ModerationFakes.wikiDiff();
        }

        public WikiText wikiText() {
            return ModerationFakes.wikiText();
        }

        public ModerationEditGuard editGuard() {
            return new ModerationEditGuard(service(), support(), pageStore());
        }

        public ModerationActionGuard actionGuard() {
            return new ModerationActionGuard(service(), support());
        }

        public ModerationEditFormField editFormField() {
            return new ModerationEditFormField(service(), support());
        }

        public ModerationPageDisplayHook pageDisplayHook() {
            return new ModerationPageDisplayHook(service(), support(), wikiText());
        }

        public ModerationSpecialPage specialPage() {
            return new ModerationSpecialPage(service(), support(), pageStore(), wikiDiff(), userStore());
        }

        /** 注册一个用户（返回用户 ID）。 */
        public long addUser(String username, String... userGroups) {
            long id = names.size() + 1L;
            names.put(id, username);
            groups.put(id, new LinkedHashSet<>(List.of(userGroups)));
            return id;
        }

        /** 登记一个页面（供队列差异 / 待审创建判断用）。 */
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

        /** 已登录用户视图（用户组按 {@code groups} 里的登记）。 */
        public Viewer viewer(long userId) {
            return new Viewer(userId, names.getOrDefault(userId, "用户" + userId), false,
                    groups.getOrDefault(userId, Set.of()), false);
        }
    }
}
