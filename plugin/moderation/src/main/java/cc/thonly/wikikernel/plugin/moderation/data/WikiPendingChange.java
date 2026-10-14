package cc.thonly.wikikernel.plugin.moderation.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import cc.thonly.wikikernel.common.data.DbType;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 待审更改（仿 MediaWiki Moderation 扩展的审核队列）。
 * <p>
 * 审核功能开启（{@code features.moderation}）时，没有豁免权的用户提交的
 * <b>页面编辑 / 创建、文件上传、文件删除</b> 不会立即生效，而是排进这里；
 * 核查员 / 管理员在 {@code 特殊:审核} 里通过（应用变更）或拒绝（丢弃）。
 * <p>
 * 页面编辑在通过后由 {@code PageWriter} 以<b>原提交人</b>的身份生成版本（与 Moderation 一致），
 * 审核动作本身记入通用日志（{@code moderation} / {@code moderationreject}）。
 * <p>
 * 表 {@code wiki_pending_change} **属于审核插件自己**：插件在
 * {@code ModerationPlugin.schemaEntities()} 里声明本实体，core 的 Auto-DDL 在建表 / 补列，
 * 数据访问走 {@code common.service.PluginDatabase}（见 {@code service/ModerationStoreJdbc}）。
 * 实体字段与迁移前的 {@code common.data.WikiPendingChange} 完全一致（列名 / 列类型不变）。
 */
@Data
@TableName("wiki_pending_change")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPendingChange {

    /** 变更类型：页面编辑 / 创建。 */
    public static final String TYPE_EDIT = "edit";
    /** 变更类型：上传文件。 */
    public static final String TYPE_UPLOAD = "upload";
    /** 变更类型：删除文件。 */
    public static final String TYPE_FILE_DELETE = "filedelete";

    /** 状态：待审。 */
    public static final String STATUS_PENDING = "pending";
    /** 状态：已通过。 */
    public static final String STATUS_APPROVED = "approved";
    /** 状态：已拒绝。 */
    public static final String STATUS_REJECTED = "rejected";

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 变更类型（{@link #TYPE_EDIT} / {@link #TYPE_UPLOAD} / {@link #TYPE_FILE_DELETE}）。 */
    @DbColumn(nullable = false, length = 16)
    private String changeType;

    /** 目标页面标题（编辑）；文件相关变更存文件页标题（{@code 文件:X}）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String title = "";

    /** 页面 ID（新建页面为 null）。 */
    @DbColumn
    private Long pageId;

    /** 提交人用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 摘要 / 说明 / 原因。 */
    @DbColumn(length = 500, defaultValue = "")
    private String comment = "";

    /** 待审内容（页面编辑 / 创建）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String content;

    /** 小编辑标记（页面编辑）。 */
    @DbColumn(type = DbType.BOOLEAN, defaultValue = "0")
    private Boolean minor = false;

    /** 提交时的基准版本 ID（页面编辑，可空；用于检测「提交后页面又被别人改过」）。 */
    @DbColumn
    private Long baseRevisionId;

    /** 是否为「AI 代理」协助的提交（审核队列里给审核员标注）。 */
    @DbColumn
    private Boolean aiAssisted = false;

    /** AI 厂商 / 模型（aiAssisted=true 时记录）。 */
    @DbColumn(length = 120)
    private String aiModel = "";

    /** 文件名（上传）。 */
    @DbColumn(length = 255)
    private String fileName;

    /** 文件说明（上传）。 */
    @DbColumn(length = 500)
    private String fileDescription;

    /** 待审文件在磁盘上的暂存名（相对插件数据目录的 {@code pending/}；上传）。 */
    @DbColumn(length = 255)
    private String fileStoredName;

    /** 文件大小（上传，展示用）。 */
    @DbColumn
    private Long fileSize;

    /** 文件 ID（删除文件）。 */
    @DbColumn
    private Long fileId;

    /** 是否彻底删除（删除文件，{@code hard=true}）。 */
    @DbColumn(type = DbType.BOOLEAN, defaultValue = "0")
    private Boolean hardDelete = false;

    /** 状态（{@link #STATUS_PENDING} / {@link #STATUS_APPROVED} / {@link #STATUS_REJECTED}）。 */
    @DbColumn(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    /** 提交时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;

    /** 审核人用户 ID（未审核为 null）。 */
    @DbColumn
    private Long reviewerId;

    /** 审核时间（未审核为 null）。 */
    @DbColumn
    private LocalDateTime reviewedAt;

    /** 审核意见（可空）。 */
    @DbColumn(length = 500)
    private String reviewComment;
}
