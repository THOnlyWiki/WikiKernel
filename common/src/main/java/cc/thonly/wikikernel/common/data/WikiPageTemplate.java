package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模板使用索引（{@code wiki_page_template}）：一行 = 「某页面直接调用了某个模板」。
 * <p>
 * 模板标题是规范化的（带命名空间按原样，否则第一个模板前缀 + 名字，见
 * {@code WikiLinkScanner.canonicalTemplateTitle}），反查时把查询标题展开成候选键匹配，
 * 因此 {@code {{问候}}} / {@code {{模板:问候}}} / {@code {{Template:问候}}} 都能查到。
 * <p>
 * 只在页面保存 / 删除时实时重建该页的行（{@code PageTemplateEventListener}），
 * 启动时全量回填；查询（反查哪些页面用了模板）仍会对最新正文做一次实时扫描核对，索引过期行不会出现。
 */
@Data
@TableName("wiki_page_template")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPageTemplate {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 使用模板的页面 ID（{@code wiki_page.id}）。 */
    @DbColumn(nullable = false)
    private Long pageId;

    /** 模板的规范化标题（如 {@code 模板:问候}）。 */
    @DbColumn(nullable = false)
    private String templateTitle;
}
