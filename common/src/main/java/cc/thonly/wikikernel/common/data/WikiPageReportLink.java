package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wiki_page_report_link")
public class WikiPageReportLink {
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    @DbColumn(nullable = false)
    private Long pageId;

    @DbColumn(nullable = false)
    private Long revisionId;

    @DbColumn(nullable = false, length = 16)
    private String linkKind;

    @DbColumn(nullable = false, type = DbType.TEXT)
    private String linkTarget;
}
