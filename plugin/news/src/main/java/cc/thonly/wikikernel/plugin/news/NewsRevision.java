package cc.thonly.wikikernel.plugin.news;
import cc.thonly.wikikernel.common.data.*;
import cc.thonly.wikikernel.common.data.DbType;
import com.baomidou.mybatisplus.annotation.*;
@TableName("wiki_news_revision")
public class NewsRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    @DbColumn
    private Long newsId;
    @DbColumn
    private Long version;
    @DbColumn(type = DbType.TEXT)
    private String payload;
    @DbColumn
    private String action;
    @DbColumn
    private Long actorId;
    @DbColumn
    private String createdAt;
}
