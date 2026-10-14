package cc.thonly.wikikernel.plugin.usermerge.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link UserMergeStore} 的插件实现（迁移前是 core 的 {@code UserMergeStoreImpl} +
 * {@code UserMergeServiceImpl}）：**数据访问属于插件自己**——事务内用 {@link PluginDatabase}
 * （裸 SQL）把源账号的引用改到目标账号，与 core 的表结构保持一致：
 * <ol>
 *     <li>编辑历史：{@code wiki_revision.user_id}；</li>
 *     <li>日志：{@code wiki_log.actor_user_id} 与 {@code wiki_log.target_user_id}；</li>
 *     <li>权限：{@code wiki_user_permission}（并入目标，同组按有效期合并：任一永久 = 永久，
 *         否则取更晚的到期时间；重复的源记录删除）；</li>
 *     <li>文件：{@code wiki_file.uploader_id} / {@code wiki_file.deleted_by} /
 *         {@code wiki_file_version.uploader_id}；</li>
 *     <li>登录 / 邮件令牌：源账号的 {@code wiki_login_token} / {@code wiki_email_token} 作废；</li>
 *     <li>可选删除源账号（{@code wiki_user}）；</li>
 *     <li>写一条 {@code usermerge} 日志（详情 {@code merge|<源名>|<deleted|kept>}，由插件的
 *         {@code UserMergeLogType} 本地化）。</li>
 * </ol>
 * 行为逐条对照迁移前的 core 实现（含空用户名视为找不到、源 = 目标、有效期合并规则、错误返回）；
 * 权限判定按「核查员」组查询用户组（组名与 core 的 {@code PermissionService.GROUP_CHECKUSER} 一致）。
 */
@Service
public class UserMergeStoreJdbc implements UserMergeStore {

    /** 与 core {@code PermissionService.GROUP_CHECKUSER} 一致的用户组名（手动分配）。 */
    private static final String GROUP_CHECKUSER = "核查员";

    /** 日志类型 id（由插件的 {@code UserMergeLogType} 注册）。 */
    private static final String LOG_TYPE_USERMERGE = "usermerge";

    private final PluginDatabase database;
    private final UserStore userStore;
    private final LogWriter logWriter;

    public UserMergeStoreJdbc(PluginDatabase database, UserStore userStore, LogWriter logWriter) {
        this.database = database;
        this.userStore = userStore;
        this.logWriter = logWriter;
    }

    @Override
    public boolean canMerge(Viewer viewer) {
        return viewer != null && viewer.loggedIn()
                && userStore.groups(viewer.id()).contains(GROUP_CHECKUSER);
    }

    @Override
    public Result merge(Viewer performer, String sourceName, String targetName, boolean deleteSource) {
        // 事务内跨表重写（与迁移前 core 的 @Transactional 一致）：抛异常 / 出错时整体回滚
        return database.transaction(() -> mergeInTransaction(performer, sourceName, targetName, deleteSource));
    }

    /** 合并的完整流程（迁移前 {@code UserMergeServiceImpl.merge} 的逐条复刻）。 */
    private Result mergeInTransaction(Viewer performer, String sourceName, String targetName,
                                      boolean deleteSource) {
        DbRow source = findByUsername(sourceName);
        if (source == null) {
            return Result.SOURCE_NOT_FOUND;
        }
        DbRow target = findByUsername(targetName);
        if (target == null) {
            return Result.TARGET_NOT_FOUND;
        }
        long sourceId = source.longValue("id", 0);
        long targetId = target.longValue("id", 0);
        if (sourceId == targetId) {
            return Result.SAME_USER;
        }

        // 1) 编辑历史
        database.update("UPDATE wiki_revision SET user_id = ? WHERE user_id = ?", targetId, sourceId);
        // 2) 日志：操作者与用户目标
        database.update("UPDATE wiki_log SET actor_user_id = ? WHERE actor_user_id = ?", targetId, sourceId);
        database.update("UPDATE wiki_log SET target_user_id = ? WHERE target_user_id = ?", targetId, sourceId);
        // 3) 权限：并入目标（同组按有效期合并；重复的源记录删除）
        mergePermissions(sourceId, targetId);
        // 4) 文件：上传者 / 删除者 / 历史版本上传者
        database.update("UPDATE wiki_file SET uploader_id = ? WHERE uploader_id = ?", targetId, sourceId);
        database.update("UPDATE wiki_file SET deleted_by = ? WHERE deleted_by = ?", targetId, sourceId);
        database.update("UPDATE wiki_file_version SET uploader_id = ? WHERE uploader_id = ?", targetId, sourceId);
        // 5) 登录 / 邮件令牌：源账号作废
        database.update("DELETE FROM wiki_login_token WHERE user_id = ?", sourceId);
        database.update("DELETE FROM wiki_email_token WHERE user_id = ?", sourceId);
        // 6) 可选删除源账号
        if (deleteSource) {
            database.update("DELETE FROM wiki_user WHERE id = ?", sourceId);
        }
        // 7) 日志（源账号名与是否删除）
        logWriter.record(LOG_TYPE_USERMERGE, actorId(performer), null, targetId,
                "merge|" + source.string("username") + "|" + (deleteSource ? "deleted" : "kept"));
        return Result.OK;
    }

    /**
     * 把源账号的权限并入目标账号（逐条对照迁移前 core 的 {@code UserMergeServiceImpl} 第 3 步）：
     * 目标没有同组记录时直接改 {@code user_id}；有同组记录时把有效期合并到目标记录、删掉源记录。
     * <p>
     * 与 core 一致：{@code permission_type → 记录} 的映射在合并过程中不随有效期更新（重复的同组
     * 源记录都按目标记录的原始有效期合并），并保留这一细节。
     */
    private void mergePermissions(long sourceId, long targetId) {
        Map<Long, Permission> targetByType = new LinkedHashMap<>();
        for (DbRow row : database.query(
                "SELECT id, permission_type, expires_at FROM wiki_user_permission WHERE user_id = ?", targetId)) {
            targetByType.put(row.longOrNull("permission_type"),
                    new Permission(row.longValue("id", 0), row.dateTime("expires_at")));
        }
        for (DbRow row : database.query(
                "SELECT id, permission_type, expires_at FROM wiki_user_permission WHERE user_id = ?", sourceId)) {
            Long permissionType = row.longOrNull("permission_type");
            long permissionId = row.longValue("id", 0);
            Permission existing = targetByType.get(permissionType);
            if (existing == null) {
                database.update("UPDATE wiki_user_permission SET user_id = ? WHERE id = ?", targetId, permissionId);
                targetByType.put(permissionType,
                        new Permission(permissionId, row.dateTime("expires_at")));
                continue;
            }
            LocalDateTime merged = mergeExpiry(existing.expiresAt(), row.dateTime("expires_at"));
            if (!Objects.equals(existing.expiresAt(), merged)) {
                database.update("UPDATE wiki_user_permission SET expires_at = ? WHERE id = ?",
                        merged, existing.id());
            }
            database.update("DELETE FROM wiki_user_permission WHERE id = ?", permissionId);
        }
    }

    /**
     * 按用户名查用户（与迁移前 {@code UserMergeServiceImpl.findByUsername} 一致：去空白、
     * 精确匹配、{@code LIMIT 1}；空用户名 = 找不到）。只取合并需要的 id / username。
     */
    private DbRow findByUsername(String username) {
        String value = Strings.trimToEmpty(username);
        if (value.isEmpty()) {
            return null;
        }
        return database.queryOne("SELECT id, username FROM wiki_user WHERE username = ? LIMIT 1", value)
                .orElse(null);
    }

    /** 日志操作者：只有已登录的执行者才记 ID（与迁移前 core 的 {@code UserMergeStoreImpl} 一致）。 */
    private static Long actorId(Viewer performer) {
        return performer != null && performer.loggedIn() ? performer.id() : null;
    }

    /** 合并两条同组授权的有效期：任一为永久（null）= 永久，否则取更晚的到期时间。 */
    private static LocalDateTime mergeExpiry(LocalDateTime first, LocalDateTime second) {
        if (first == null || second == null) {
            return null;
        }
        return first.isAfter(second) ? first : second;
    }

    /** 一条用户权限记录（合并有效期用）。 */
    private record Permission(long id, LocalDateTime expiresAt) {
    }
}
