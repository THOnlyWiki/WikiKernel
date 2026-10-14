package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;

import java.util.List;
import java.util.Optional;

/**
 * 审核服务（仿 MediaWiki Moderation 扩展；原 core 的 {@code service/ModerationService} 整体迁入）。
 * <p>
 * 功能开关 {@code features.moderation} 开启后：
 * <ul>
 *     <li><b>审核人</b>：「核查员」「管理员」——可查看 特殊:审核 队列、通过 / 拒绝变更；</li>
 *     <li><b>豁免组</b>：「免审查用户」「页面管理员」「小部件管理员」「小部件编辑者」——提交直接生效，不进队列；</li>
 *     <li>其余用户的页面编辑 / 创建、文件上传、文件删除进入队列，通过后以原提交人身份应用变更。</li>
 * </ul>
 * 数据：队列存插件自己的表 {@code wiki_pending_change}（{@code ModerationStoreJdbc}），
 * 待审上传的文件内容暂存在插件数据目录下的 {@code pending/}（{@code PendingFileStoreImpl}）；
 * 通过待审编辑用 {@code common.service.PageWriter} 写页面、通过待审上传 / 删除用
 * {@code common.service.FileWriter} 写文件（core 的 {@code FileService} 仍是唯一写入方）。
 */
public interface ModerationService {

    /** 审核功能是否启用（{@code features.moderation}，可在 /init 或 特殊:权限配置 里开关）。 */
    boolean enabled();

    /** 是否审核人（核查员 / 管理员；匿名 / 未登录为 false）。 */
    boolean isReviewer(Viewer viewer);

    /** 是否豁免审核（免审查用户 / 页面管理员 / 小部件管理员 / 小部件编辑者）。 */
    boolean isExempt(Viewer viewer);

    /** 这次提交是否需要审核（功能开启 && 已登录 && 非豁免）。 */
    boolean shouldModerate(Viewer viewer);

    // ==================== 入队 ====================

    /**
     * 页面编辑 / 创建进审核队列（普通提交）。
     *
     * @param pageId         目标页面 ID（新建页面为 null）
     * @param baseRevisionId 提交时的基准版本（页面不存在为 null）
     * @param aiAssisted     是否为 AI 代理协助的提交（通过后的版本同样带 AI 水印）
     * @param aiModel        AI 厂商 / 模型（{@code aiAssisted=true} 时记录）
     */
    WikiPendingChange submitEdit(Viewer submitter, Long pageId, Long baseRevisionId, String title,
                                 String content, String comment, boolean minor,
                                 boolean aiAssisted, String aiModel);

    /** 文件上传进审核队列（文件内容暂存磁盘，通过后才写入正式存储）。 */
    WikiPendingChange submitUpload(Viewer submitter, String fileName, String description, byte[] content);

    /** 文件删除进审核队列。 */
    WikiPendingChange submitFileDelete(Viewer submitter, long fileId, String fileName, boolean hard, String reason);

    // ==================== 查询 ====================

    /** 待审上传的文件内容（从磁盘暂存读取；不存在 / 不是上传时返回空）。 */
    Optional<byte[]> pendingFileData(long id);

    /** 某页面（含新建）的待审变更数量。 */
    long pendingCount(String title);

    /** 按状态统计条数（空白 = 全部）。 */
    long countByStatus(String status);

    /** 某页面的待审变更（倒序）。 */
    List<WikiPendingChange> pendingForPage(String title);

    /**
     * 队列查询。
     *
     * @param status    pending / approved / rejected / 空白 = 全部
     * @param type      edit / upload / filedelete / 空白 = 全部
     * @param titleLike 标题片段（空白 = 全部；包含匹配）
     * @param limit     最多返回条数
     */
    List<WikiPendingChange> list(String status, String type, String titleLike, int limit);

    Optional<WikiPendingChange> find(long id);

    // ==================== 审核 ====================

    /** 通过：应用变更（页面编辑以原提交人身份生成版本、上传写入正式存储、删除真正执行）并记日志。 */
    ReviewResult approve(Viewer reviewer, long id, String comment);

    /** 拒绝：丢弃变更（不产生任何版本 / 文件变化）并记日志。 */
    ReviewResult reject(Viewer reviewer, long id, String comment);

    /** 审核结果。 */
    enum ReviewResult {
        /** 成功。 */
        OK,
        /** 变更不存在。 */
        NOT_FOUND,
        /** 已被审核过（重复操作）。 */
        ALREADY_REVIEWED,
        /** 应用变更失败（文件已被删 / 写入失败等）。 */
        APPLY_FAILED
    }
}
