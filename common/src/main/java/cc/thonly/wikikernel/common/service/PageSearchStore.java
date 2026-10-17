package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 页面检索（给 DynamicPageList 这类「按条件列出页面」的扩展用）：
 * 过滤条件在 core 里执行，插件只拿结果，不直接依赖 {@code wiki_page} / {@code wiki_page_category} 表结构。
 * <p>
 * 与 {@link PageQueryStore} 的分工：{@code PageQueryStore} 是「按前缀 / 命名空间列举」的轻量查询；
 * 这里额外支持分类、排序方式、标题匹配与偏移量，供 DPL 的 {@code #dpl} 使用。
 */
public interface PageSearchStore {

    /**
     * 按条件检索页面（只返回未删除、未隐藏的页面）。
     *
     * @param query 查询条件；{@code null} 返回空列表
     * @return 命中的页面（已按 {@code offset} / {@code limit} 截取）
     */
    List<PageRow> query(PageQuery query);

    /**
     * 页面查询条件。
     *
     * @param namespace      命名空间：空白 / {@code main} = 主命名空间；{@code all} = 全部；稳定 id 或前缀（如 {@code template} / {@code 模板}）
     * @param categories     必须同时属于这些分类（AND；分类名可写 {@code 分类:X} 或 {@code X}）
     * @param notCategories  不能属于这些分类（任一命中即排除）
     * @param titlePrefix    标题前缀（可空）
     * @param titleContains  标题包含（可空，忽略大小写）
     * @param titleRegex     标题正则（可空，忽略大小写；与 {@code titleContains} 同时存在时都要满足）
     * @param titleNotRegex  标题不能匹配的正则（可空）
     * @param orderMethod    {@code title} / {@code firstedit} / {@code lastedit} / {@code pagetouched} / {@code none}
     * @param descending     是否降序
     * @param offset         跳过前 N 条（从 0 开始）
     * @param limit          最多返回多少条；{@code <= 0} 用默认值
     * @param redirects      {@code include}（默认）/ {@code exclude} / {@code only}
     */
    record PageQuery(
            String namespace,
            List<String> categories,
            List<String> notCategories,
            String titlePrefix,
            String titleContains,
            String titleRegex,
            String titleNotRegex,
            String orderMethod,
            boolean descending,
            int offset,
            int limit,
            String redirects
    ) {
        public PageQuery {
            categories = categories == null ? List.of() : List.copyOf(categories);
            notCategories = notCategories == null ? List.of() : List.copyOf(notCategories);
        }
    }

    /**
     * 检索结果行。
     *
     * @param id               页面 id
     * @param title            标题（含命名空间）
     * @param latestRevisionId 最新版本 id（可空）
     * @param createdAt        页面创建时间（DPL 的 firstedit）
     * @param updatedAt        页面最后更新时间（DPL 的 lastedit / pagetouched）
     */
    record PageRow(long id, String title, Long latestRevisionId,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
