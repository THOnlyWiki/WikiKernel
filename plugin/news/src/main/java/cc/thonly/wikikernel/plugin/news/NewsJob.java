package cc.thonly.wikikernel.plugin.news;
import cc.thonly.wikikernel.common.data.*;
import cc.thonly.wikikernel.common.data.DbType;
import com.baomidou.mybatisplus.annotation.*;
@TableName("wiki_news_job")
public class NewsJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    @DbColumn(unique = true, length = 80)
    private String dedupKey;
    @DbColumn
    private Long actorId;
    @DbColumn(type = DbType.TEXT)
    private String input;
    @DbColumn(length = 2048)
    private String sourceUrl;
    @DbColumn
    private String state;
    @DbColumn
    private Long newsId;
    @DbColumn
    private String error;
    @DbColumn
    private String createdAt;
    @DbColumn private String leasedAt;
}
