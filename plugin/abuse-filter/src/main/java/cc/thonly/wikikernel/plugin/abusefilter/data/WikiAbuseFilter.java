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
 * 滥用过滤器（仿 MediaWiki 的 AbuseFilter 扩展，表 {@code abuse_filter}）：
 * {@link #pattern} 是过滤条件表达式；命中 {@code actions} 里的操作时执行；
 * 每次修改都会写入 {@link WikiAbuseFilterRevision} 历史。
 */
@Data
@TableName("wiki_abuse_filter")
@AllArgsConstructor
@NoArgsConstructor
public class WikiAbuseFilter {

    /** 过滤器编号（MediaWiki 的 af_id）。 */
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 公开说明（命中时显示在警告 / 阻止信息里）。 */
    @DbColumn(length = 255)
    private String description = "";

    /** 过滤条件（AbuseFilter 表达式语言）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String pattern = "";

    /** 执行的操作（逗号分隔：disallow / warn / tag / throttle / block / blockautopromote / degrade）。 */
    @DbColumn(length = 255)
    private String actions = "";

    /** tag 操作要添加的标签（逗号分隔；留空 = abusefilter-编号）。 */
    @DbColumn(length = 255)
    private String tags = "";

    /** 节流参数：期间内允许的操作数（配合 {@link #throttlePeriod}）。 */
    @DbColumn
    private Integer throttleCount = 0;

    /** 节流期间（秒）。 */
    @DbColumn
    private Integer throttlePeriod = 0;

    /** 节流分组：user / ip / page / user-ip 等（默认 user）。 */
    @DbColumn(length = 32)
    private String throttleGroup = "user";

    /** 是否启用。 */
    @DbColumn
    private Boolean enabled = false;

    /** 是否已删除（软删除，保留历史）。 */
    @DbColumn
    private Boolean deleted = false;

    /** 是否隐藏（隐藏的过滤器只有管理员可见，命中日志同样隐藏）。 */
    @DbColumn
    private Boolean hidden = false;

    /** 过滤器注释（仅管理员编辑页可见，不进入命中提示）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String notes = "";

    /** warn / degrade 操作用的自定义警告文案；留空 = 使用说明。 */
    @DbColumn(length = 255)
    private String warnMessage = "";

    /** warn / degrade 的备用信息页标题（文案为空时读取页面正文）。 */
    @DbColumn(length = 255)
    private String warnPage = "";

    /** disallow 操作用的自定义阻止文案；留空 = 使用说明。 */
    @DbColumn(length = 255)
    private String disallowMessage = "";

    /** disallow 的备用信息页标题（文案为空时读取页面正文）。 */
    @DbColumn(length = 255)
    private String disallowPage = "";

    /** 封禁时是否同时禁止编辑讨论页（当前 core 封禁桥不接收该参数，仅保存配置）。 */
    @DbColumn
    private Boolean blockTalk = false;

    /** 匿名用户封禁天数；0 = 不限期。 */
    @DbColumn
    private Integer blockAnonDays = 3;

    /** 注册用户封禁天数；0 = 不限期。 */
    @DbColumn
    private Integer blockUserDays = 3;

    /** 命中次数（累计）。 */
    @DbColumn
    private Integer hits = 0;

    /** 最后修改者。 */
    @DbColumn
    private Long lastEditorId;

    /** 最后修改时间。 */
    @DbColumn
    private LocalDateTime lastEditAt;

    /** 创建时间。 */
    @DbColumn
    private LocalDateTime createAt;
}
