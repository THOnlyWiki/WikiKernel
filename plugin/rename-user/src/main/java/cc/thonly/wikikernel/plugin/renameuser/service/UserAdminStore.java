package cc.thonly.wikikernel.plugin.renameuser.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理桥（重命名用户，仿 MediaWiki 的 Renameuser 扩展）：**数据访问属于插件自己**——实现
 * {@link UserAdminStoreJdbc} 经 {@code common.service.PluginDatabase}（JDBC）直接读写 core 的
 * 用户 / 页面 / 日志表（core 表用裸 SQL 读写，表结构变化时插件自己跟进）。
 * <p>
 * 方法签名与迁移前 core 的 {@code common.service.UserAdminStore} 完全一致，只把包名换到插件里：
 * 「账号改名 + 用户名规则校验 + 写 {@code renameuser} 日志 + 可选移动用户页」全部在
 * {@link UserAdminStoreJdbc} 里完成；插件页面只负责表单、接口、权限文案与结果映射。
 * <p>
 * 权限判定（{@link #canRename(Viewer)}）按「重命名用户」组（{@code 重命名用户}，默认无人拥有，
 * 管理员可授予；组名与 core 的 {@code PermissionService.GROUP_RENAME_USER} 一致）查询用户组，
 * 匿名恒为 false。
 */
public interface UserAdminStore {

    /** 重命名结果。 */
    enum RenameResult {
        /** 改名成功。 */
        OK,
        /** 找不到旧用户。 */
        NO_SUCH_USER,
        /** 新用户名已被占用。 */
        NAME_IN_USE,
        /** 新旧用户名相同。 */
        SAME_NAME,
        /** 新用户名非法（空 / 含 {@code # < > [ ] | { } /} 等字符）。 */
        INVALID_NAME
    }

    /** 按用户名查用户（不存在返回空；不区分大小写，与 core 的 {@code UserService.findByUsername} 一致）。 */
    Optional<WikiUser> byName(String name);

    /** 按 ID 查用户（不存在返回空）。 */
    Optional<WikiUser> byId(long id);

    /** 当前用户是否拥有「重命名用户」权限（匿名恒为 false；组名与 core 的 {@code PermissionService} 一致）。 */
    boolean canRename(Viewer viewer);

    /**
     * 重命名用户：校验新用户名 → 改 {@code wiki_user.username} → 写 {@code renameuser} 日志 →
     * 可选把 {@code 用户:旧名} / {@code 用户讨论:旧名} 及其子页面改名（存在才移动、目标已存在则跳过）。
     *
     * @param actor     执行者（写日志的操作者；匿名 / 空为系统）
     * @param userId    要改名的用户 ID
     * @param oldName   旧用户名（日志动作句显示旧名；账号已改名后按 ID 解析会变成新名，所以显式传入）
     * @param newName   新用户名
     * @param reason    原因（可空，进日志详情）
     * @param movePages 是否一并移动用户页 / 用户讨论页（含子页面）
     */
    RenameResult rename(Viewer actor, long userId, String oldName, String newName, String reason,
                        boolean movePages);

    /**
     * 最近的重命名日志（{@code renameuser} 类型，新的在前），已按当前语言格式化成展示行
     * （含隐藏日志；迁移前由 core 的 {@code LogService} + {@code LogFormatter} 生成，键名不变）。
     */
    List<RenameLogRow> recentRenames(int limit);

    /** 最近重命名日志的一行（已本地化，插件模板直接渲染）。 */
    record RenameLogRow(LocalDateTime time, String actorName, String actionBefore, String targetText,
                        String targetUrl, String actionAfter, String detailText) {
    }
}
