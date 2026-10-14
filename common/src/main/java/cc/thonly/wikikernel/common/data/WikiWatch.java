package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户的监视列表条目（仿 MediaWiki 的 watchlist）：用户 ↔ 页面标题。
 */
@Data
@TableName("wiki_watch")
@AllArgsConstructor
@NoArgsConstructor
public class WikiWatch {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 用户 ID（wiki_user.id）。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 被监视的页面标题（规范标题）。 */
    @DbColumn(nullable = false)
    private String title;

    /** 加入监视列表的时间。 */
    @DbColumn
    private LocalDateTime createdAt;
}
