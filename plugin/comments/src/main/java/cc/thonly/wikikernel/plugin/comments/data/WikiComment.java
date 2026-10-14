package cc.thonly.wikikernel.plugin.comments.data;

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
 * 页面评论（{@code <comment />} 标签控制是否显示）：
 * 支持回复（parentId）与 wikitext 内容；删除为软删除（保留占位，作者的评论自己可以删，
 * 「评论管理员」/ 管理员 / 行政员可以删任何评论）；发表与删除都会写日志。
 * <p>
 * 表 {@code wiki_comment} **属于评论插件自己**：插件在
 * {@code CommentsPlugin.schemaEntities()} 里声明本实体，core 的 Auto-DDL 在建表 / 补列，
 * 数据访问走 {@code common.service.PluginDatabase}（见 {@code service/CommentStoreJdbc}）。
 * 实体字段与迁移前的 {@code common.data.WikiComment} 完全一致（列名 / 列类型不变）。
 */
@Data
@TableName("wiki_comment")
@AllArgsConstructor
@NoArgsConstructor
public class WikiComment {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 所属页面标题。 */
    @DbColumn(nullable = false, length = 255)
    private String pageTitle;

    /** 回复的父评论 id（顶层评论为空）。 */
    @DbColumn
    private Long parentId;

    /** 发表者用户 ID。 */
    @DbColumn
    private Long userId;

    /** 评论内容（wikitext，原样存储，展示时渲染）。 */
    @DbColumn(type = DbType.TEXT)
    private String content = "";

    /** 是否已删除（软删除：内容不再显示，保留占位与回复关系）。 */
    @DbColumn(defaultValue = "0")
    private Boolean deleted = false;

    /** 删除者用户 ID。 */
    @DbColumn
    private Long deletedBy;

    /** 删除时间。 */
    @DbColumn
    private LocalDateTime deletedAt;

    /** 发表时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
