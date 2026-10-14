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
 * 滥用过滤器的历史版本（仿 MediaWiki 的 {@code abuse_filter_history}）：
 * 每次保存过滤器都会插入一条，可查看并恢复。
 */
@Data
@TableName("wiki_abuse_filter_revision")
@AllArgsConstructor
@NoArgsConstructor
public class WikiAbuseFilterRevision {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 所属过滤器编号。 */
    @DbColumn(nullable = false)
    private Long filterId;

    @DbColumn(length = 255)
    private String description = "";

    @DbColumn(type = DbType.LONGTEXT)
    private String pattern = "";

    @DbColumn(length = 255)
    private String actions = "";

    @DbColumn(length = 255)
    private String tags = "";

    @DbColumn
    private Integer throttleCount = 0;

    @DbColumn
    private Integer throttlePeriod = 0;

    @DbColumn(length = 32)
    private String throttleGroup = "user";

    @DbColumn
    private Boolean enabled = false;

    @DbColumn
    private Boolean deleted = false;

    @DbColumn
    private Boolean hidden = false;

    /** 修改者（null = 系统）。 */
    @DbColumn
    private Long userId;

    /** 修改说明。 */
    @DbColumn(length = 255)
    private String comment = "";

    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
