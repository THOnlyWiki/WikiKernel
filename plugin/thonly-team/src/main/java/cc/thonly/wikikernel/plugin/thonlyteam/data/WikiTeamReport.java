package cc.thonly.wikikernel.plugin.thonlyteam.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 举报记录。
 *
 * <p>不自动处置：写一条记录 + 写通用日志，站长在日志里能看到，
 * 判定后再用「解散」处理。自动封禁留给站长手动决定，避免误伤。
 */
@Data
@TableName("wiki_team_report")
public class WikiTeamReport {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    @DbColumn(nullable = false)
    private Long teamId;

    @DbColumn
    private Long reporterId;

    @DbColumn(length = 255)
    private String reporterName;

    @DbColumn(length = 500)
    private String reason;

    /** 站长是否已处理 */
    @DbColumn
    private Boolean handled;

    @DbColumn
    private LocalDateTime createAt;
}
