package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 最近编辑 / 编辑者概况的**只读**桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 这些数据在 core 的 revision / page / user 表里，插件拿不到 Mapper；给「需要一批带正文的最近编辑
 * 与编辑者统计」的功能用（例如滥用过滤器的「测试过滤器」预览、活动流、机器人 / 巡查类扩展）。
 * 只读：插件不要指望通过它改任何东西。
 */
public interface RecentChangesStore {

    /**
     * 一行最近编辑（只读快照）。
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

    /**
     * 编辑者概况。
     *
     * @param editCount    编辑次数
     * @param registeredAt 注册时间（为空表示未知）
     */
    record EditorStats(long editCount, LocalDateTime registeredAt) {
    }

    /** 最近的编辑（新的在前，未被删除的版本；{@code limit} 会被夹在 1..500）。 */
    List<RecentEdit> recentEdits(int limit);

    /** 某个用户的概况（用户不存在返回空）。 */
    Optional<EditorStats> editorStats(long userId);
}
