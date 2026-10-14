package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;

import java.util.List;

/**
 * 页面分类索引（{@code wiki_page_category}）：
 * 页面保存时按“模板展开后的渲染结果”重建；分类页 / 页面信息 / 分类列表都读索引。
 * 模板修改时会顺带重建其链入页面（{@code reindexDependents}），保证模板里的分类及时生效。
 */
public interface PageCategoryService {

    /** 重建某个页面的分类索引（页面不存在 / 已删除则清空）。 */
    void reindex(long pageId);

    /** 按标题重建（页面不存在则忽略）。 */
    void reindex(String title);

    /** 清空某个页面的分类索引（页面删除时）。 */
    void clear(long pageId);

    /** 重建使用该模板的页面（模板修改后调用；按原文扫描依赖，最多 200 个页面）。 */
    void reindexDependents(String templateTitle);

    /** 分类成员（按排序键 → 标题排序）。 */
    List<Member> members(String categoryTitle, int limit);

    /** 分类成员：页面 + 排序键（可空）。 */
    record Member(WikiPage page, String sortKey) {
    }

    /** 页面归入的分类（规范标题 + 排序键，保持插入顺序）。 */
    List<WikiLinkScanner.CategoryRef> categoriesOf(String pageTitle);

    /** 全部分类与成员数（分类列表页）。 */
    List<CategoryCount> allCategories(int limit);

    /** 分类：标题 + 成员数。 */
    record CategoryCount(String title, int count) {
    }
}
