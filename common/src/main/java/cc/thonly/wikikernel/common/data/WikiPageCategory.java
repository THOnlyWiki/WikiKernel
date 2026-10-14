package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 页面分类索引（{@code wiki_page_category}）：页面保存时按“模板展开后的渲染结果”重建。
 * <p>
 * 模板里写的 {@code [[分类:X]]} 同样会把嵌入它的页面归入分类（与 MediaWiki 的 categorylinks 一致）；
 * 模板修改时其链入页面会一并重建（见 {@code PageCategoryServiceImpl#reindexDependents}）。
 */
@Data
@TableName("wiki_page_category")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPageCategory {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 页面 ID（wiki_page.id）。 */
    @DbColumn(nullable = false)
    private Long pageId;

    /** 规范分类标题（{@code 分类:X}）。 */
    @DbColumn(nullable = false)
    private String categoryTitle;

    /** 排序键（{@code [[分类:X|键]]}；可空 = 用页面标题排序）。 */
    @DbColumn(length = 255)
    private String sortKey;
}
