package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通用操作日志（创建/编辑/删除页面、用户注册、权限变更……）。
 * <p>
 * 日志类型由 {@code LogType} 注册中心管理（core Bean 或插件注册），
 * 类型 id 存在 {@link #logType}；{@link #hided} 为 true 的日志只有管理员/行政员可见。
 */
@Data
@TableName("wiki_log")
@AllArgsConstructor
@NoArgsConstructor
public class WikiLog {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 日志类型 id（见 LogType 注册中心，如 create / edit / delete / register / rights）。 */
    @DbColumn(nullable = false, length = 32)
    private String logType;

    /** 操作者用户 ID（匿名或系统操作为 null）。 */
    @DbColumn
    private Long actorUserId;

    /** 文本目标：页面标题等（可空）。 */
    @DbColumn(length = 255)
    private String target;

    /** 用户目标：注册 / 权限变更等（可空）。 */
    @DbColumn
    private Long targetUserId;

    /** 详情：编辑摘要、权限变更（如 {@code grant|管理员}）等（可空）。 */
    @DbColumn(length = 500)
    private String detail;

    /** 是否被隐藏（隐藏后仅管理员 / 行政员可见）。 */
    @DbColumn
    private Boolean hided = false;

    /** 操作者 IP（可空：旧数据 / 无请求上下文；CDN 场景下为真实客户端 IP，见 wikikernel.http）。 */
    @DbColumn(length = 64)
    private String ip;

    /** 操作者 User-Agent（可空，核查用）。 */
    @DbColumn(length = 500)
    private String userAgent;

    /** 发生时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime time;

}
