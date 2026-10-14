package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 滥用过滤器数据桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 滥用过滤器的实体在 {@code common.data}（{@code WikiAbuseFilter} / {@code WikiAbuseFilterRevision} /
 * {@code WikiAbuseLog}），Mapper 与建表都在 core（Auto-DDL），插件只通过这个桥读写，
 * **只做数据访问**：不评估条件、不写日志文案、不做权限判断。
 * <p>
 * 除了三张滥用表，桥还提供「测试过滤器」需要的只读查询（最近编辑 / 用户概况）——
 * 这些数据在 core 的 revision / user 表里，插件拿不到 Mapper，所以由这里统一暴露。
 */
public interface AbuseFilterStore {

    // ==================== 过滤器（wiki_abuse_filter） ====================

    /** 过滤器列表（新的在前）；{@code includeHidden=false} 时不返回隐藏过滤器。 */
    List<WikiAbuseFilter> filters(boolean includeHidden);

    /** 按编号查过滤器（不存在返回空）。 */
    Optional<WikiAbuseFilter> find(long filterId);

    /**
     * 保存 / 新建过滤器：{@code id} 为空时插入并回填自增主键，否则按主键更新。
     *
     * @return 过滤器编号（新建时为数据库生成的编号）
     */
    long save(WikiAbuseFilter filter);

    /** 修改启用 / 软删除 / 隐藏标记（{@code null} = 该项不改）；过滤器不存在返回 false。 */
    boolean updateFlags(long filterId, Boolean enabled, Boolean deleted, Boolean hidden);

    /** 命中次数 +1（原子自增，不经过实体读写）。 */
    void bumpHits(long filterId);

    /** 删除过滤器及其全部历史版本；不存在返回 false（界面上的「删除」用软删除标记，这里是数据层删除）。 */
    boolean delete(long filterId);

    // ==================== 修订历史（wiki_abuse_filter_revision） ====================

    /** 写入一条修订（保存过滤器时调用）。 */
    void insertRevision(WikiAbuseFilterRevision revision);

    /** 某个过滤器的修订历史（新的在前）。 */
    List<WikiAbuseFilterRevision> history(long filterId);

    /** 按编号查修订（不存在返回空）。 */
    Optional<WikiAbuseFilterRevision> findRevision(long revisionId);

    // ==================== 滥用日志（wiki_abuse_log） ====================

    /** 写入一条命中记录（{@link WikiAbuseLog#getVariables()} 原样入库）。 */
    void insertLog(WikiAbuseLog entry);

    /** 命中记录（新的在前，最多 {@code limit} 条）；{@code includeHided=false} 时不返回隐藏条目。 */
    List<WikiAbuseLog> logs(boolean includeHided, int limit);

    /** 按编号查命中记录（变量快照 JSON 原样返回；不存在返回空）。 */
    Optional<WikiAbuseLog> findLog(long logId);

    /** 隐藏 / 显示一条命中记录；不存在返回 false。 */
    boolean hideLog(long logId, boolean hided);

    // ==================== 测试过滤器需要的只读查询 ====================

    /**
     * 测试页用的一行最近编辑（只读快照）。
     *
     * @param revisionId 版本号
     * @param pageId     页面 ID
     * @param pageTitle  页面标题（含命名空间）
     * @param userId     编辑者 ID（匿名 = null）
     * @param userName   编辑者用户名（匿名 / 找不到 = 空串）
     * @param time       编辑时间
     * @param summary    编辑摘要
     * @param content    该版本正文
     * @param oldContent 上一个版本的正文（没有 = 空串）
     */
    record RecentEdit(long revisionId, long pageId, String pageTitle, Long userId, String userName,
                      LocalDateTime time, String summary, String content, String oldContent) {
    }

    /** 最近的编辑（新的在前，未被删除的版本，最多 {@code limit} 条）。 */
    List<RecentEdit> recentEdits(int limit);

    /**
     * 过滤变量需要的用户概况。
     *
     * @param editCount    编辑次数（{@code user_editcount}）
     * @param registeredAt 注册时间（{@code user_age} 用；为空表示未知）
     */
    record UserStats(long editCount, LocalDateTime registeredAt) {
    }

    /** 用户概况（用户不存在返回空）。 */
    Optional<UserStats> userStats(long userId);
}
