package cc.thonly.wikikernel.plugin.renameuser.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.PageCache;
import cc.thonly.wikikernel.common.service.PageQueryStore;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserSupport;
import cc.thonly.wikikernel.plugin.renameuser.hook.RenameUserLogType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link UserAdminStore} 的插件实现（迁移前是 core 的 {@code UserAdminStoreImpl} +
 * {@code UserService.rename}）：**数据访问属于插件自己**——改名用 {@link PluginDatabase}
 * （裸 SQL）写 {@code wiki_user.username}，用户页移动用裸 SQL 改 {@code wiki_page.title}，
 * 与 core 的表结构保持一致。
 * <p>
 * 行为逐条对照迁移前的 core 实现：
 * <ul>
 *     <li>用户名规则与注册一致（{@code # < > [ ] | { } /} 非法；见
 *         {@code UserService.FORBIDDEN_USERNAME_CHARS}，插件自己重新实现）；</li>
 *     <li>用户读取走通用的 {@link UserStore}（{@code byName} 不区分大小写的回退与 core 一致）；</li>
 *     <li>页面存在性 / 目标占用走通用的 {@link PageQueryStore}（标题规范化 / 命名空间别名与 core 一致）；</li>
 *     <li>写 {@code renameuser} 日志（详情 {@code rename|<新名>|<原因>}）与移动页面的 {@code move}
 *         日志（目标 = 新标题，详情 = 原标题）；页面正文缓存按新标题失效；</li>
 *     <li>用户页标题按 core 的 {@code WikiTitles.normalize} 规则规范化（折叠空白 +
 *         命名空间后首字符大写，开关 {@code wikikernel.title.capitalize-first-letter}）。</li>
 * </ul>
 * 权限判定按「重命名用户」组查询用户组（组名与 core 的 {@code PermissionService.GROUP_RENAME_USER} 一致）。
 */
@Service
public class UserAdminStoreJdbc implements UserAdminStore {

    /** 一次请求最多移动的子页面数（防止大批量页面拖垮请求；与迁移前 core 一致）。 */
    private static final int SUBPAGE_MOVE_LIMIT = 200;

    /** 与用户名对应的页面命名空间（正文 + 讨论页）。 */
    private static final List<String> USER_NAMESPACES = List.of("用户:", "用户讨论:");

    /** 与 core {@code PermissionService.GROUP_RENAME_USER} 一致的用户组名。 */
    private static final String GROUP_RENAME_USER = "重命名用户";

    /** 用户名里禁止出现的字符（与 core {@code UserService.FORBIDDEN_USERNAME_CHARS} 一致）。 */
    private static final String FORBIDDEN_USERNAME_CHARS = "#<>[]|{}/";

    /** 日志类型 id（由插件的 {@code RenameUserLogType} 注册）。 */
    private static final String LOG_TYPE_RENAMEUSER = "renameuser";

    /** core 的移动日志类型 id（core 的 {@code LogTypeConfig.MOVE}）。 */
    private static final String LOG_TYPE_MOVE = "move";

    private final PluginDatabase database;
    private final UserStore users;
    private final PageQueryStore pages;
    private final PageCache pageCache;
    private final LogWriter logWriter;
    private final RenameUserSupport support;
    private final boolean capitalizeFirstLetter;
    /** 详情文案由插件注册的日志类型本地化（与 core 的 {@code LogFormatter} 优先问类型自己一致）。 */
    private final RenameUserLogType logType;

    public UserAdminStoreJdbc(PluginDatabase database, UserStore users, PageQueryStore pages,
                              PageCache pageCache, LogWriter logWriter, RenameUserSupport support,
                              @Value("${wikikernel.title.capitalize-first-letter:true}")
                              boolean capitalizeFirstLetter) {
        this.database = database;
        this.users = users;
        this.pages = pages;
        this.pageCache = pageCache;
        this.logWriter = logWriter;
        this.support = support;
        this.capitalizeFirstLetter = capitalizeFirstLetter;
        this.logType = new RenameUserLogType(support);
    }

    @Override
    public Optional<WikiUser> byName(String name) {
        return users.byName(name).map(UserAdminStoreJdbc::toUser);
    }

    @Override
    public Optional<WikiUser> byId(long id) {
        return users.byId(id).map(UserAdminStoreJdbc::toUser);
    }

    @Override
    public boolean canRename(Viewer viewer) {
        return viewer != null && viewer.loggedIn()
                && users.groups(viewer.id()).contains(GROUP_RENAME_USER);
    }

    @Override
    public RenameResult rename(Viewer actor, long userId, String oldName, String newName, String reason,
                               boolean movePages) {
        String fresh = Strings.trimToEmpty(newName);
        WikiUser target = byId(userId).orElse(null);
        if (target == null) {
            return RenameResult.NO_SUCH_USER;
        }
        // 统一用账号里记录的用户名（输入可能只差大小写，页面前缀要以真实用户名为准）
        String currentName = target.getUsername();
        if (currentName.equals(fresh)) {
            return RenameResult.SAME_NAME;
        }
        if (!isValidUsername(fresh)) {
            return RenameResult.INVALID_NAME;
        }
        if (users.exists(fresh)) {
            return RenameResult.NAME_IN_USE;
        }
        if (!applyUsername(userId, fresh)) {
            // 并发下新名被抢注（或服务层防御不通过）：按已被占用处理
            return RenameResult.NAME_IN_USE;
        }
        if (movePages) {
            moveUserPages(userId, currentName, fresh);
        }
        // 日志目标保留旧用户名（账号已改名，按 targetUserId 解析会变成新名）
        logWriter.record(LOG_TYPE_RENAMEUSER, actor == null ? null : actor.id(), currentName, userId,
                "rename|" + fresh + "|" + Strings.trimToEmpty(reason));
        return RenameResult.OK;
    }

    // ==================== 改名 ====================

    /**
     * 改 {@code wiki_user.username}（迁移前 {@code UserService.rename} 的服务层防御：
     * 新名合法、与旧名不同、未被占用；用户不存在 / 被并发抢注返回 false）。
     */
    private boolean applyUsername(long userId, String newUsername) {
        String value = Strings.trimToEmpty(newUsername);
        if (!isValidUsername(value)) {
            return false;
        }
        WikiUser user = byId(userId).orElse(null);
        if (user == null || value.equals(user.getUsername())) {
            return false;
        }
        if (users.exists(value)) {
            return false;
        }
        return database.update("UPDATE wiki_user SET username = ? WHERE id = ?", value, userId) > 0;
    }

    /**
     * 新用户名是否合法：去空白后非空，且不含 {@value #FORBIDDEN_USERNAME_CHARS} 等字符
     * （与 core 的 {@code UserService.isValidUsername} 一致；规则在插件里重新实现）。
     */
    private static boolean isValidUsername(String username) {
        String value = Strings.trimToEmpty(username);
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (FORBIDDEN_USERNAME_CHARS.indexOf(value.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== 用户页移动 ====================

    /**
     * 把 用户:旧名 / 用户讨论:旧名 的页面与子页面改名为新名（存在才移动、目标已存在则跳过），
     * 返回移动数量。子页面做前缀替换，总数上限 {@link #SUBPAGE_MOVE_LIMIT}。
     */
    private int moveUserPages(long actorUserId, String oldName, String newName) {
        int moved = 0;
        int budget = SUBPAGE_MOVE_LIMIT;
        for (String namespace : USER_NAMESPACES) {
            String from = namespace + oldName;
            String to = userPageTitle(namespace, newName);
            moved += moveIfPossible(actorUserId, from, to);
            if (budget <= 0) {
                continue;
            }
            // 与 core 的 likeRight 查询一致：不筛已删除（已删除的子页面在 moveIfPossible 里被跳过）
            List<DbRow> subpages = database.query(
                    "SELECT id, title FROM wiki_page WHERE title LIKE ? ORDER BY title ASC LIMIT ?",
                    from + "/%", budget);
            for (DbRow subpage : subpages) {
                String title = subpage.string("title");
                if (title.length() >= from.length()) {
                    moved += moveIfPossible(actorUserId, title, to + title.substring(from.length()));
                }
                budget--;
            }
        }
        return moved;
    }

    /** 移动单个页面（源存在且目标未被占用时才移动），返回是否移动（与 core 的 {@code moveIfPossible} 一致）。 */
    private int moveIfPossible(long actorUserId, String from, String to) {
        if (from.equals(to)) {
            return 0;
        }
        Optional<PageQueryStore.PageDetail> source = pages.detail(from);
        if (source.isEmpty() || pages.detail(to).isPresent()) {
            return 0;
        }
        PageQueryStore.PageDetail page = source.get();
        // 与 core 的 WikiPageService.movePage 一致：改标题 + update_at；日志目标 = 新标题，详情 = 原标题
        if (database.update("UPDATE wiki_page SET title = ?, update_at = ? WHERE id = ?",
                to, LocalDateTime.now(), page.id()) <= 0) {
            return 0;
        }
        logWriter.record(LOG_TYPE_MOVE, actorUserId, to, null, page.title());
        // core 的 WikiPageChangedEvent（移动后）会让页面正文缓存按新标题失效；插件在这里直接失效
        pageCache.invalidate(to);
        return 1;
    }

    /**
     * 用户名页标题的规范化（与 core 的 {@code WikiTitles.normalize} 对用户命名空间的结果一致）：
     * 折叠空白（下划线 / 连续空白 → 一个空格，去首尾、去 Unicode 双向控制符）后，
     * 命名空间后的首字符按 {@code wikikernel.title.capitalize-first-letter}（默认开）大写。
     */
    private String userPageTitle(String namespace, String name) {
        String value = foldWhitespace(stripBidiControls(namespace + name));
        if (!capitalizeFirstLetter || value.length() <= namespace.length()) {
            return value;
        }
        return namespace + capitalizeFirst(value.substring(namespace.length()));
    }

    // ==================== 最近的重命名 ====================

    @Override
    public List<RenameLogRow> recentRenames(int limit) {
        int effective = Math.max(1, limit);
        List<DbRow> rows = database.query(
                "SELECT id, actor_user_id, target, target_user_id, detail, time FROM wiki_log"
                        + " WHERE log_type = ? ORDER BY id DESC LIMIT ?",
                LOG_TYPE_RENAMEUSER, effective);
        Set<Long> userIds = new LinkedHashSet<>();
        for (DbRow row : rows) {
            Long actorUserId = row.longOrNull("actor_user_id");
            Long targetUserId = row.longOrNull("target_user_id");
            if (actorUserId != null) {
                userIds.add(actorUserId);
            }
            if (targetUserId != null) {
                userIds.add(targetUserId);
            }
        }
        Map<Long, String> userNames = users.names(userIds);
        List<RenameLogRow> result = new ArrayList<>(rows.size());
        for (DbRow row : rows) {
            result.add(toRow(row, userNames));
        }
        return result;
    }

    /** 一行日志 → 展示行（迁移前 core 的 {@code LogFormatter} 对 renameuser 的规则）。 */
    private RenameLogRow toRow(DbRow row, Map<Long, String> userNames) {
        Long actorUserId = row.longOrNull("actor_user_id");
        Long targetUserId = row.longOrNull("target_user_id");
        String targetCaption = Strings.trimToEmpty(row.string("target"));
        // 目标：用户类型 → 用户名（找不到退化为 #ID）；renameuser 日志保存了旧用户名，优先用它
        String targetText = targetUserId == null
                ? "" : userNames.getOrDefault(targetUserId, "#" + targetUserId);
        if (!targetCaption.isEmpty()) {
            targetText = targetCaption;
        }
        String detail = Strings.trimToEmpty(row.string("detail"));
        String detailText = "";
        if (!detail.isEmpty()) {
            String formatted = logType.detailText(detail);
            detailText = formatted == null || formatted.isBlank() ? detail : formatted;
        }
        // 动作句按 {0} 拆成「目标前 / 目标后」两段（模板把目标渲染成链接）
        String displayName = support.message("log.typeName.renameuser", "renameuser");
        String pattern = support.message("log.entry.renameuser", displayName + " ");
        int index = pattern.indexOf("{0}");
        return new RenameLogRow(
                row.dateTime("time"),
                actorUserId == null ? null : userNames.get(actorUserId),
                index < 0 ? pattern : pattern.substring(0, index),
                targetText,
                targetText.isEmpty() ? null : RenameUserSupport.pagePath("用户:" + targetText),
                index < 0 ? "" : pattern.substring(index + 3),
                detailText);
    }

    // ==================== 小工具 ====================

    private static WikiUser toUser(UserStore.UserRef ref) {
        WikiUser user = new WikiUser();
        user.setId(ref.id());
        user.setUsername(ref.username());
        return user;
    }

    /** 首字符大写（只影响一个码点；与 core 的 {@code WikiTitles.capitalizeFirst} 一致）。 */
    private static String capitalizeFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int codePoint = value.codePointAt(0);
        int upper = Character.toUpperCase(codePoint);
        if (upper == codePoint) {
            return value;
        }
        int charCount = Character.charCount(codePoint);
        return new StringBuilder(value.length())
                .appendCodePoint(upper).append(value, charCount, value.length()).toString();
    }

    /** 去掉 Unicode 双向控制符（与 core 的 {@code WikiTitles} 一致）。 */
    private static String stripBidiControls(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u200E' || c == '\u200F' || (c >= '\u202A' && c <= '\u202E')) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 折叠空白（下划线 / 连续空白 → 一个空格，去首尾；与 core 的 {@code WikiTitles} 一致）。 */
    private static String foldWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isWhitespace(c)) {
                if (!previousSpace) {
                    out.append(' ');
                    previousSpace = true;
                }
            } else {
                out.append(c);
                previousSpace = false;
            }
        }
        return out.toString().trim();
    }

    /** MediaWiki 会把下划线以及各种 Unicode 空白折叠成空格（含 NBSP / 全角空格）。 */
    private static boolean isWhitespace(char c) {
        return c == '_' || c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\u00A0' || c == '\u1680' || c == '\u180E' || c == '\u202F' || c == '\u205F'
                || c == '\u2028' || c == '\u2029' || c == '\u3000'
                || (c >= '\u2000' && c <= '\u200A');
    }
}
