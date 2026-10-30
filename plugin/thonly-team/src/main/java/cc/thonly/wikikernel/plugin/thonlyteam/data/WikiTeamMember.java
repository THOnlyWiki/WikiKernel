package cc.thonly.wikikernel.plugin.thonlyteam.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 队伍成员。{@code status} 区分「已加入」和「待批准」，退队是直接删行。 */
@Data
@TableName("wiki_team_member")
public class WikiTeamMember {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    @DbColumn(nullable = false)
    private Long teamId;

    @DbColumn(nullable = false)
    private Long userId;

    @DbColumn(length = 255)
    private String userName;

    /** owner 团长 / member 队员 */
    @DbColumn(length = 16)
    private String role;

    /** joined 已加入 / pending 待批准 / rejected 已拒绝 */
    @DbColumn(length = 16)
    private String status;

    @DbColumn
    private LocalDateTime createAt;
}
