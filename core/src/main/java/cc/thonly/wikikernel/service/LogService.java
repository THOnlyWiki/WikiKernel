package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通用操作日志服务：写入日志、查询日志、隐藏/显示日志。
 * <p>
 * 写入时只存“结构化”数据（类型 id、目标、详情前缀），展示文案由
 * {@code LogFormatter} 按当前语言生成，保证日志可本地化。
 */
public interface LogService {

    /**
     * 记一条日志。
     *
     * @param logType      类型 id（{@code LogType} 注册中心里的 id）
     * @param actorUserId  操作者（匿名/系统为 null）
     * @param target       文本目标（页面标题等，可空）
     * @param targetUserId 用户目标（注册/权限变更等，可空）
     * @param detail       详情（编辑摘要、{@code grant|管理员} 等，可空）
     */
    void record(String logType, Long actorUserId, String target, Long targetUserId, String detail);

    /**
     * 查日志（与某用户相关：TA 操作的 或 关于 TA 的）。
     *
     * @param logType       类型过滤（空白表示全部）
     * @param relatedUserId 相关用户（null 表示不过滤）
     * @param includeHided  是否包含已隐藏日志（管理员 / 行政员传 true）
     */
    default List<WikiLog> recent(String logType, Long relatedUserId, boolean includeHided, int limit) {
        return search(logType, relatedUserId, null, null, null, includeHided, limit);
    }

    /**
     * 日志搜索（{@code 特殊:日志} 的筛选栏，仿 MediaWiki Special:Log）：
     * 可按类型、用户（操作者或目标）、目标页面片段与时间范围组合过滤。
     *
     * @param logType       类型过滤（空白表示全部）
     * @param relatedUserId 相关用户：TA 操作的或关于 TA 的（null 表示不过滤）
     * @param target        目标页面标题片段（空白表示不过滤，包含匹配）
     * @param start         起始时间（含，null 表示不限）
     * @param end           结束时间（含，null 表示不限）
     * @param includeHided  是否包含已隐藏日志（管理员 / 行政员传 true）
     * @param limit         最多返回条数
     */
    default List<WikiLog> search(String logType, Long relatedUserId, String target,
                                 LocalDateTime start, LocalDateTime end, boolean includeHided, int limit) {
        return search(logType, relatedUserId, target, start, end, includeHided, limit, 0);
    }

    /**
     * 带偏移的日志搜索（{@code 特殊:日志} 的分页）：按 id 倒序，先 OFFSET 再 LIMIT。
     *
     * @param offset 跳过前多少条（从 0 开始）
     */
    List<WikiLog> search(String logType, Long relatedUserId, String target,
                         LocalDateTime start, LocalDateTime end, boolean includeHided,
                         int limit, int offset);

    /** 查关于某用户的日志（仅 targetUserId 匹配，权限页用）。 */
    List<WikiLog> recentForTarget(String logType, Long targetUserId, boolean includeHided, int limit);

    /** 某用户“自己操作”的最近日志（用户核查：TA 用过的 IP / UA；只看 actor，不看目标）。 */
    List<WikiLog> recentByActor(Long actorUserId, boolean includeHided, int limit);

    /**
     * 按 IP（精确地址或 IPv4 CIDR，如 {@code 203.0.113.0/24}）查最近日志（用户核查：这个 IP 是谁在用）。
     * CIDR 不支持时按精确匹配处理。
     */
    List<WikiLog> recentByIp(String ipOrCidr, int limit);

    /** 隐藏 / 显示一条日志。 */
    void setHided(long logId, boolean hided);
}
