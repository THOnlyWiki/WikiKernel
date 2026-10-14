package cc.thonly.wikikernel.plugin.usermerge.service;

import cc.thonly.wikikernel.common.extension.Viewer;

/**
 * 用户合并桥（仿 MediaWiki 的 UserMerge 扩展）：**数据访问属于插件自己**——实现
 * {@link UserMergeStoreJdbc} 经 {@code common.service.PluginDatabase}（JDBC）直接读写 core 的
 * 用户 / 编辑历史 / 日志 / 权限 / 文件 / 令牌表（core 表用裸 SQL 读写，表结构变化时插件自己跟进）。
 * <p>
 * 方法签名与迁移前 core 的 {@code common.service.UserMergeStore} 完全一致，只把包名换到插件里：
 * 把源账号的数据（编辑历史 / 日志 / 权限 / 文件）归入目标账号、可选删除源账号、写 {@code usermerge}
 * 日志，全部由 {@link UserMergeStoreJdbc} 在事务内完成。插件页面只负责表单、接口、权限文案与结果映射。
 * <p>
 * 权限判定（{@link #canMerge(Viewer)}）按「核查员」用户组（{@code 核查员}，手动分配；组名与 core 的
 * {@code PermissionService.GROUP_CHECKUSER} 一致）查询用户组，匿名恒为 false。
 */
public interface UserMergeStore {

    /** 合并结果（与迁移前 core 的 {@code UserMergeService.Result} 一一对应）。 */
    enum Result {
        /** 合并成功。 */
        OK,
        /** 找不到源用户。 */
        SOURCE_NOT_FOUND,
        /** 找不到目标用户。 */
        TARGET_NOT_FOUND,
        /** 源用户与目标用户相同。 */
        SAME_USER
    }

    /** 当前用户是否拥有「核查员」权限（匿名恒为 false；组名与 core 的 {@code PermissionService} 一致）。 */
    boolean canMerge(Viewer viewer);

    /**
     * 把 {@code sourceName} 合并到 {@code targetName}。
     *
     * @param performer    执行者（{@code CurrentUser} 的查看者；写 usermerge 日志的操作者）
     * @param sourceName   源用户名
     * @param targetName   目标用户名
     * @param deleteSource 合并后是否删除源账号（false = 保留空账号）
     */
    Result merge(Viewer performer, String sourceName, String targetName, boolean deleteSource);
}
