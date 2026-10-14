package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;

import java.util.List;
import java.util.Optional;

/**
 * 待审更改数据访问（审核插件自己的表 {@code wiki_pending_change}）。
 * <p>
 * 只管队列表的读写；「谁需要审核、通过后怎么应用变更」由 {@link ModerationService} 决定。
 * 待审上传的文件内容用 {@link PendingFileStore} 暂存 / 读取。
 * <p>
 * 实现是插件子容器里的 {@link ModerationStoreJdbc}（走 {@code common.service.PluginDatabase}
 * 裸 SQL）；表结构由 {@code ModerationPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列。
 * 方法签名与迁移前的 {@code common.service.ModerationStore} 完全一致。
 */
public interface ModerationStore {

    /** 新入队（返回带自增 ID 的实体）。 */
    WikiPendingChange insert(WikiPendingChange change);

    /** 更新（审核状态、审核人、审核意见等）。 */
    void update(WikiPendingChange change);

    /** 按 ID 查。 */
    Optional<WikiPendingChange> find(long id);

    /**
     * 队列列表（新的在前）。
     *
     * @param status    状态过滤（空白 = 全部）
     * @param type      类型过滤（空白 = 全部）
     * @param titleLike 标题片段（空白 = 不过滤，包含匹配）
     * @param limit     最多返回条数（{@code <= 0} = 不限制）
     */
    List<WikiPendingChange> list(String status, String type, String titleLike, int limit);

    /** 某页面（含「待审创建」）的待审变更（倒序）。 */
    List<WikiPendingChange> forPage(String title);

    /** 待审条数（{@code status} 空白 = 全部）。 */
    long count(String status);

    /** 某页面在指定状态下的条数（{@code status} 空白 = 全部）。 */
    long countForPage(String title, String status);

    /** 某用户提交的待审变更（倒序；供「我的待审提交」这类界面用）。 */
    List<WikiPendingChange> byUser(long userId, int limit);
}
