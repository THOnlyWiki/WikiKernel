package cc.thonly.wikikernel.plugin.news;
import cc.thonly.wikikernel.common.data.*;
import cc.thonly.wikikernel.common.data.DbType;
import com.baomidou.mybatisplus.annotation.*;
@TableName("wiki_news")
public class NewsItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    @DbColumn(type = DbType.TEXT)
    private String draft;
    @DbColumn(type = DbType.TEXT)
    private String published;
    @DbColumn
    private String status;
    @DbColumn
    private Long version;
    @DbColumn
    private Long ownerId;
    @DbColumn
    private String updatedAt;
    @DbColumn
    private String createdAt;
    @DbColumn private String releasedAt;
    @DbColumn private String expiresAt;
    @DbColumn private String pinUntil;
    @DbColumn private String publicCategory;
    @DbColumn private String publicRegion;
    @DbColumn(length = 500) private String publicSearch;
}
