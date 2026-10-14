package cc.thonly.wikikernel.common.service;

import java.util.List;

/**
 * 分类索引访问桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 数据来自 core 维护的分类索引 {@code wiki_page_category}（页面保存时按「模板展开后的渲染结果」重建）：
 * 分类成员查询、页面所属分类、成员统计。
 */
public interface CategoryStore {

    /**
     * 某个分类的直接成员（按排序键 / 标题排序）。
     *
     * @param categoryTitle 分类标题（{@code 分类:X}、{@code Category:X} 或裸名 {@code X}，core 会规范化）
     * @param limit         最多返回多少条（&lt;= 0 表示不限制）
     */
    List<CategoryMember> members(String categoryTitle, int limit);

    /** 页面所属的分类（规范标题 {@code 分类:X}，按加入顺序；含「红分类」）。 */
    List<String> categoriesOf(long pageId);

    /** 分类成员统计（子分类 / 内容页面 / 文件 / 全部）。 */
    CategoryCounts counts(String categoryTitle);
}
