package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wiki 页面版本（一行 = 一次编辑，正文在这里）。
 * <p>
 * 对照 MediaWiki 的 revision 表：{@link #id} 每次编辑由数据库自增（版本 ID），
 * {@link #pageId} 指向 {@link WikiPage#getId()}，同一页面的所有版本靠它串成历史。
 */
@Data
@TableName("wiki_revision")
@AllArgsConstructor
@NoArgsConstructor
public class WikiRevision {

    /** 版本 ID（数据库自增，每次编辑 +1）。 */
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 所属页面 ID（wiki_page.id）。 */
    @DbColumn(nullable = false)
    private Long pageId;

    /** 版本正文（Wikitext）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String content;

    /** 编辑者 ID（匿名编辑为 null）。 */
    @DbColumn
    private Long userId;

    /** 编辑摘要。 */
    @DbColumn(length = 500)
    private String comment = "";

    /** 是否为机器人编辑（「机器人」组用户保存时勾选；默认不在最近更改中显示）。 */
    @DbColumn
    private Boolean bot = false;

    /** 是否为小编辑（MediaWiki 的 minor edit 标记）。 */
    @DbColumn
    private Boolean minor = false;

    /** 变更标签（逗号分隔，如滥用过滤器命中的标签）。 */
    @DbColumn(length = 255)
    private String tags = "";

    /** 是否为「AI 代理」协助完成的编辑（词条底部的隐藏水印据此渲染）。 */
    @DbColumn
    private Boolean aiAssisted = false;

    /** AI 厂商 / 模型（如 {@code deepseek/deepseek-chat}；aiAssisted=true 时记录，便于追溯）。 */
    @DbColumn(length = 120)
    private String aiModel = "";

    /** 此版本是否被删除/隐藏（历史页会标注，不参与展示与搜索）。 */
    @DbColumn
    private Boolean deleted = false;

    /** 版本创建（编辑）时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;

}
