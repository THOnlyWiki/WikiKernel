package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨 Wiki 前缀（仿 MediaWiki 的 {@code interwiki} 表，表 {@code wiki_interwiki}）：
 * 把 {@code [[前缀:页面]]} 这样的链接映射到另一个 wiki（或本 wiki 的其它前缀）。
 * <p>
 * 语义（与 {{@code 特殊:跨Wiki}} 页面的说明一致）：
 * <ul>
 *     <li>{@link #url} 是 URL 模板，{@code $1} 替换成 URL 编码后的页面名（不含 {@code $1} 时追加到末尾）；</li>
 *     <li>{@link #language} 非空 = <b>语言链接</b>：正文里不显示，收集到页面侧栏「语言」区块（仿 MediaWiki 跨语言链接）；</li>
 *     <li>{@link #local} = <b>本地前缀</b>：链接指向本 wiki（生成内部链接 {@code /wiki/前缀:页面}）；
 *     本地命名空间（{@code 帮助:} 等）优先于跨 Wiki 前缀；</li>
 *     <li>{@link #transcludable} = 可嵌入（MediaWiki 的 {@code iw_trans}）：<b>目前只存储</b>，
 *     尚不支持 {@code {{前缀:页面}}} 的远端嵌入（见 README）。</li>
 * </ul>
 * 前缀大小写不敏感且唯一。
 */
@Data
@TableName("wiki_interwiki")
@AllArgsConstructor
@NoArgsConstructor
public class WikiInterwiki {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 跨 Wiki 前缀（大小写不敏感、唯一；不含空格 / 冒号 / 竖线 / 方括号）。 */
    @DbColumn(nullable = false, length = 32, unique = true)
    private String prefix = "";

    /** URL 模板，例如 {@code https://zh.wikipedia.org/wiki/$1} 或 {@code https://example.org/?title=$1}。 */
    @DbColumn(nullable = false, length = 512)
    private String url = "";

    /** 语言代码（如 {@code en} / {@code ja}）；非空表示这是语言链接。 */
    @DbColumn(length = 16)
    private String language;

    /** 是否本地前缀（链接指向本 wiki，生成内部链接）。 */
    @DbColumn
    private Boolean local = false;

    /** 是否可嵌入（MediaWiki 的 iw_trans；目前仅存储，未实现远端嵌入）。 */
    @DbColumn
    private Boolean transcludable = false;
}
