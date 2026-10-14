package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wiki 页面身份（一行 = 一个页面，不存正文；正文在 {@link WikiRevision}）。
 * <p>
 * 对照 MediaWiki 的 page 表：{@link #id} 即页面 ID（数据库自增，代替原来的 pageIdentifier），
 * {@link #latestRevisionId} 指向当前版本。标题被“删除后重建”时会得到新的页面 ID，
 * 旧页面的历史仍保留在旧 ID 下。
 * <p>
 * Auto-DDL 会根据本类自动建表（wiki_page）并补齐缺失字段。
 */
@Data
@TableName("wiki_page")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPage {

    /** 页面 ID（数据库自增，历史的“页面身份”）。 */
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 页面标题。 */
    @DbColumn(nullable = false)
    private String title;

    /** 最新版本 ID，指向 wiki_revision.id。 */
    @DbColumn
    private Long latestRevisionId;

    /** 是否隐藏（隐藏页面不对外可见，历史仍保留）。 */
    @DbColumn
    private Boolean hided = false;

    /** 是否删除（删除的页面不参与查询；重建会得到新页面 ID）。 */
    @DbColumn
    private Boolean deleted = false;

    /** 是否受保护（只有管理员可以编辑/移动）。 */
    @DbColumn
    private Boolean protectedPage = false;

    /** 创建时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;

    /** 最后编辑时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime updateAt;

}
