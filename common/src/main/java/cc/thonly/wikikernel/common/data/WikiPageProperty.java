package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 页面属性（{@code wiki_page_property}，仿 MediaWiki 的 {@code page_props}）：
 * 页面渲染时由扩展点写入（如 SEO 元数据、{@code templatedata}、{@code notitle}），
 * 保存后持久化；插件也可以直接写入自己的数据（如 HitCounters 的浏览计数）。
 * <p>
 * 调用方：core 的 {@code PagePropertyStoreImpl}（对插件暴露 {@code common.service.PagePropertyStore}）。
 */
@Data
@TableName("wiki_page_property")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPageProperty {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 页面 ID（wiki_page.id）。 */
    @DbColumn(nullable = false)
    private Long pageId;

    /** 属性名（如 {@code description} / {@code templatedata} / {@code hitcounter-views}）。 */
    @DbColumn(nullable = false, length = 64)
    private String propName;

    /** 属性值（JSON / 文本 / 数字字符串）。 */
    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String propValue;
}
