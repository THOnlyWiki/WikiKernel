package cc.thonly.wikikernel.plugin.abusefilter.data;

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
 * 滥用日志（仿 MediaWiki 的 Special:AbuseLog / {@code abuse_filter_log}）：
 * 每次过滤器命中都会记录一行，{@link #variables} 是当时的变量快照（JSON）。
 */
@Data
@TableName("wiki_abuse_log")
@AllArgsConstructor
@NoArgsConstructor
public class WikiAbuseLog {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 命中的过滤器编号。 */
    @DbColumn(nullable = false)
    private Long filterId;

    /** 触发者（匿名 = null）。 */
    @DbColumn
    private Long userId;

    /** 触发者 IP。 */
    @DbColumn(length = 64)
    private String ip = "";

    /** 涉及页面标题（注册 / 移动等没有页面时为对应的目标名）。 */
    @DbColumn(length = 255)
    private String pageTitle = "";

    /** 动作（edit / create / move / createaccount）。 */
    @DbColumn(length = 32)
    private String action = "";

    /** 实际执行的操作（逗号分隔；空 = 只记录）。 */
    @DbColumn(length = 255)
    private String actionsTaken = "";

    /** 变量快照（JSON，详情页展示）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String variables = "";

    /** 是否被隐藏（隐藏条目只有管理员可见）。 */
    @DbColumn
    private Boolean hided = false;

    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
