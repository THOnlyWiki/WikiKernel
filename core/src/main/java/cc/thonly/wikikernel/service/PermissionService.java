package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPermissionType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户权限（用户组）服务。
 * <p>
 * 权限类型存 {@code wiki_permission_type}，绑定存 {@code wiki_user_permission}，变更日志存 {@code wiki_rights_log}。
 * 分组语义（对齐 MediaWiki）：
 * <ul>
 *     <li><b>基础组</b>：{@code 用户}——注册即拥有，权限页不展示、保存时始终保留；</li>
 *     <li><b>编辑自动授予组</b>：{@code 自动确定用户}——编辑满 {@link #AUTO_CONFIRM_EDITS} 次后由系统自动授予
 *     （权限页“自动用户组”一行展示；管理员也可以在权限页手动勾选 / 取消）；</li>
 *     <li><b>可手动组</b>：管理员、行政员等——权限页的复选框，保存时整体替换；</li>
 *     <li>保存会把基础组保留下来，并按“授予/移除”记入权限日志。</li>
 * </ul>
 */
public interface PermissionService {

    String GROUP_USER = "用户";
    String GROUP_AUTOCONFIRMED = "自动确定用户";
    String GROUP_EMAIL_CONFIRMED = "邮箱确认用户";
    String GROUP_ADMIN = "管理员";
    /** 界面管理员（手动组）：默认只有它能编辑 / 创建 / 移动 CSS / JS 页面（仿 MediaWiki 的 interface-admin）。 */
    String GROUP_INTERFACE_ADMIN = "界面管理员";
    /** 小部件编辑者（手动组）：默认只有它能编辑 / 创建 / 移动「微件」命名空间页面（仿 MediaWiki 的 editwidgets）。 */
    String GROUP_WIDGET_EDITOR = "小部件编辑者";
    /** MediaWiki Gadgets 的编辑管理组（对应 editgadget / editsitejs 的站点管理职责）。 */
    String GROUP_GADGET_ADMIN = "小部件管理员";
    String GROUP_BUREAUCRAT = "行政员";
    /** 核查员：可以使用「用户核查」「用户合并」等敏感工具（手动分配）。 */
    String GROUP_CHECKUSER = "核查员";
    /** 重命名用户（手动组）：可以使用「特殊:重命名用户」更改账号名（仿 MediaWiki 的 Renameuser 扩展）。 */
    String GROUP_RENAME_USER = "重命名用户";
    /** 评论管理员（手动组）：可以删除任何评论。 */
    String GROUP_COMMENT_ADMIN = "评论管理员";
    /** 机器人（手动组）：编辑可标记为机器人编辑（默认不在最近更改中显示）；也是系统账号（如滥用过滤器）的标记组。 */
    String GROUP_BOT = "机器人";
    /** 免审查用户（手动组）：审核功能开启时，其编辑 / 上传 / 删除不进入审核队列（仿 MediaWiki Moderation 的 automoderated）。 */
    String GROUP_MODERATION_EXEMPT = "免审查用户";
    /** 页面管理员（手动组）：审核豁免组之一（由管理员按需授予）。 */
    String GROUP_PAGE_ADMIN = "页面管理员";
    /** AI 代理用户（手动组）：可以使用「特殊:AI代理」让 AI 协助编辑（即使站点未放开普通用户）。 */
    String GROUP_AI_AGENT = "AI代理用户";
    /** 始终保留的基础组（不可手动增删）。 */
    Set<String> IMPLICIT_GROUPS = Set.of(GROUP_USER);

    /** 注册时默认授予的组（自动确定用户改为编辑满 {@link #AUTO_CONFIRM_EDITS} 次自动授予）。 */
    Set<String> DEFAULT_GROUPS = Set.of(GROUP_USER);

    /** 「自动确定用户」的编辑次数门槛：编辑满这么多次后由系统自动授予（记入权限日志）。 */
    int AUTO_CONFIRM_EDITS = 50;

    /** 日志详情前缀：授予权限（后接权限名）。 */
    String RIGHTS_GRANT_PREFIX = "grant|";

    /** 日志详情前缀：移除权限（后接权限名）。 */
    String RIGHTS_REVOKE_PREFIX = "revoke|";

    /** 全部权限类型（按表内顺序）。 */
    List<WikiPermissionType> allTypes();

    /**
     * 一条用户组授予记录。
     *
     * @param name      用户组名
     * @param expiresAt 过期时间（{@code null} = 永久）
     */
    record Membership(String name, LocalDateTime expiresAt) {

        /** 是否仍然有效（未过期）。 */
        public boolean active() {
            return expiresAt == null || expiresAt.isAfter(LocalDateTime.now());
        }
    }

    /** 可手动分配的权限类型（排除自动组）。 */
    default List<WikiPermissionType> changeableTypes() {
        return allTypes().stream()
                .filter(type -> !IMPLICIT_GROUPS.contains(type.getName()))
                .toList();
    }

    /** 某用户当前有效的权限名（已过期的授予不算；基础组「用户」按注册时授予）。 */
    Set<String> namesOf(Long userId);

    /** 某用户全部用户组授予记录（含已过期），按类型表顺序；权限管理页展示用。 */
    List<Membership> membershipsOf(Long userId);

    /** 某用户实际拥有的自动组（“自动用户组”一行展示用：自动确定用户 / 邮箱确认用户）。 */
    default Set<String> automaticGroupsOf(Long userId) {
        Set<String> groups = new LinkedHashSet<>(namesOf(userId));
        groups.retainAll(Set.of(GROUP_AUTOCONFIRMED, GROUP_EMAIL_CONFIRMED));
        return groups;
    }

    /** 授予某个权限（系统操作；差异记入权限日志）。 */
    default void grant(Long userId, String permissionName, Long actorUserId) {
        Set<String> names = new LinkedHashSet<>(namesOf(userId));
        names.add(permissionName);
        replaceFor(userId, names, actorUserId);
    }

    /** 移除某个权限（系统操作；差异记入权限日志）。 */
    default void revoke(Long userId, String permissionName, Long actorUserId) {
        Set<String> names = new LinkedHashSet<>(namesOf(userId));
        names.remove(permissionName);
        replaceFor(userId, names, actorUserId);
    }

    /**
     * 用「组 → 过期时间（{@code null} = 永久）」整体替换用户的权限（基础组始终保留），
     * 并把「授予 / 移除 / 有效期变更」记入权限日志（actor 可为 null = 系统）。
     */
    void replaceFor(Long userId, Map<String, LocalDateTime> memberships, Long actorUserId);

    /** 同 {@link #replaceFor(Long, Map, Long)}，另把修改原因写进权限日志（可为空）。 */
    void replaceFor(Long userId, Map<String, LocalDateTime> memberships, Long actorUserId, String reason);

    /** 用给定权限名整体替换用户的权限（全部永久；系统操作）。 */
    default void replaceFor(Long userId, Collection<String> permissionNames) {
        replaceFor(userId, permissionNames, null);
    }

    /** 用给定权限名整体替换用户的权限（全部永久），并把「授予 / 移除」记入权限日志。 */
    default void replaceFor(Long userId, Collection<String> permissionNames, Long actorUserId) {
        Map<String, LocalDateTime> memberships = new LinkedHashMap<>();
        if (permissionNames != null) {
            for (String name : permissionNames) {
                memberships.put(name, null);
            }
        }
        replaceFor(userId, memberships, actorUserId);
    }

    /** 注册后授予基础组 + 自动组。 */
    void grantDefault(Long userId);

    /**
     * 编辑次数达到 {@link #AUTO_CONFIRM_EDITS} 时自动授予「自动确定用户」
     * （系统操作、记入权限日志；已拥有则跳过，不重复写日志）。
     */
    default void grantAutoConfirmIfEligible(Long userId, long editCount) {
        if (userId == null || editCount < AUTO_CONFIRM_EDITS || has(userId, GROUP_AUTOCONFIRMED)) {
            return;
        }
        grant(userId, GROUP_AUTOCONFIRMED, null);
    }

    /** 是否拥有某个权限。 */
    boolean has(Long userId, String permissionName);

    /** 是否能管理用户权限（管理员或行政员）。 */
    default boolean canManage(Long userId) {
        return has(userId, GROUP_ADMIN) || has(userId, GROUP_BUREAUCRAT);
    }

    /** 一次性取出所有用户 → 权限名集合（权限列表页用）。 */
    Map<Long, Set<String>> allUserPermissions();
}
