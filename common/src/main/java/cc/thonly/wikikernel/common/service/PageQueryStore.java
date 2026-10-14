package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 页面列举与版本历史桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 「按前缀 / 命名空间列页面」与「读某页的版本历史」，
 * 命名空间语义、隐藏 / 删除过滤都由 core 负责（插件不需要自己抄一份命名空间表）。
 */
public interface PageQueryStore {

    /**
     * 一个页面（列举用）。
     *
     * @param id               页面 ID
     * @param title            标题（含命名空间）
     * @param latestRevisionId 最新版本 ID（可空）
     * @param updatedAt        最后更新时间（可空）
     */
    record PageEntry(long id, String title, Long latestRevisionId, LocalDateTime updatedAt) {
    }

    /**
     * 一个版本（历史 / 最新版本用）。
     *
     * @param id          版本 ID
     * @param userId      作者用户 ID（可空）
     * @param comment     编辑摘要
     * @param size        正文字节数
     * @param minor       小编辑
     * @param bot         机器人编辑
     * @param aiAssisted  是否 AI 协助（隐藏水印）
     * @param aiModel     AI 厂商 / 模型（{@code provider/model}）
     * @param deleted     是否被版本删除（历史里过滤掉）
     * @param createdAt   时间
     */
    record RevisionEntry(long id, Long userId, String comment, int size, boolean minor, boolean bot,
                         boolean aiAssisted, String aiModel, boolean deleted, LocalDateTime createdAt) {
    }

    /**
     * 一个页面的元信息（读页面 / 页面信息用；比 {@link PageSnapshot} 多保护状态与创建时间）。
     *
     * @param id               页面 ID
     * @param title            标题（含命名空间）
     * @param latestRevisionId 最新版本 ID（可空）
     * @param protectedPage    是否被保护
     * @param createdAt        创建时间（可空）
     * @param updatedAt        最后更新时间（可空）
     */
    record PageDetail(long id, String title, Long latestRevisionId, boolean protectedPage,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 全部命名空间的稳定 id（{@code main} / {@code template} / {@code module} / {@code widget} / …）。 */
    List<String> namespaces();

    /** 按标题查页面元信息（不存在 / 已删除返回空；不区分大小写）。 */
    Optional<PageDetail> detail(String title);

    /**
     * 列出页面（未删除 / 未隐藏，标题升序）。
     *
     * @param prefix    标题前缀（空 = 不筛选；可以写「模板:」这类命名空间前缀）
     * @param namespace 命名空间稳定 id 或前缀名（{@code main} / {@code template} / {@code 模板:} …；
     *                  空 / {@code all} / 未知 = 不筛选）
     * @param limit     最多返回多少条（&lt;= 0 = 默认上限）
     */
    List<PageEntry> list(String prefix, String namespace, int limit);

    /** 最新版本（页面不存在 / 没有版本时为空）。 */
    Optional<RevisionEntry> latestRevision(long pageId);

    /**
     * 版本历史（最新在前）。
     *
     * @param pageId 页面 ID
     * @param limit  最多返回多少条（&lt;= 0 = 不限制）
     */
    List<RevisionEntry> history(long pageId, int limit);
}
