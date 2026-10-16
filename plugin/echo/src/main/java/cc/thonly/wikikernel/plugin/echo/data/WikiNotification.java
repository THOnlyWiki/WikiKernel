package cc.thonly.wikikernel.plugin.echo.data;

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
 * 一条 Echo 通知（仿 MediaWiki Echo 扩展的 {@code echo_event} + {@code echo_notification}：
 * 这里合为一张表，一行 = 一个收件人的一条通知）。
 * <p>
 * 表 {@code wiki_notification} 属于 Echo 插件自己：插件在 {@code EchoPlugin.schemaEntities()}
 * 里声明本实体，core 的 Auto-DDL 建表 / 补列；数据访问走 {@code common.service.PluginDatabase}
 * （见 {@code service.EchoNotificationStoreJdbc}）。
 *
 * <h3>通知类型（{@code type}）</h3>
 * <ul>
 *     <li>{@code edit-user-talk}：有人编辑了你的用户讨论页；</li>
 *     <li>{@code mention}：有人在你参与过的页面 / 你的讨论页里 {@code [[用户:你]]} / {@code @你} 提及了你；</li>
 *     <li>{@code revert}：你最近的编辑被管理员 / 行政员回退（rollback）；</li>
 *     <li>{@code welcome}：新用户欢迎（注册后第一条通知，服务端写入一次）。</li>
 * </ul>
 */
@Data
@TableName("wiki_notification")
@AllArgsConstructor
@NoArgsConstructor
public class WikiNotification {

    /** 通知 ID（数据库自增）。 */
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 收件人用户 ID。 */
    @DbColumn(nullable = false)
    private Long recipientUserId;

    /** 通知类型（{@code edit-user-talk} / {@code mention} / {@code revert} / {@code welcome}）。 */
    @DbColumn(nullable = false, length = 40)
    private String type;

    /** 触发者用户 ID（系统通知如 welcome 为 null）。 */
    @DbColumn
    private Long actorUserId;

    /** 涉及页面标题（含命名空间；welcome 为 null）。 */
    @DbColumn(length = 255)
    private String pageTitle;

    /** 涉及修订 ID（跳「差异 / 历史」用；可为 null）。 */
    @DbColumn
    private Long revisionId;

    /** 摘要 / 附加说明（页面标题里的段落名等；可为 null）。 */
    @DbColumn(length = 500)
    private String comment;

    /** 已读标记（0 = 未读）。 */
    @DbColumn(defaultValue = "0")
    private Boolean read = false;

    /** 已读时间。 */
    @DbColumn
    private LocalDateTime readAt;

    /** 创建时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;

    /** 是否需要渲染正文（提及通知可渲染上下文；0 = 只显示摘要）。 */
    @DbColumn(defaultValue = "0")
    private Boolean needRender = false;

    /** 上下文正文（需渲染时存 wikitext，展示时用同一套渲染器转 HTML）。 */
    @DbColumn(type = DbType.TEXT)
    private String contextText = "";
}