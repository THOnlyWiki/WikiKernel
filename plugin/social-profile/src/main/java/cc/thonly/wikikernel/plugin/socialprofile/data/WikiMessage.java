package cc.thonly.wikikernel.plugin.socialprofile.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import cc.thonly.wikikernel.common.data.DbType;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户间私信：收件人阅读后标记已读；收件人 / 发件人都可以软删除自己一侧（fromDeleted / toDeleted），
 * 任意一侧仍存在时记录保留。
 */
@Data
@TableName("wiki_message")
@AllArgsConstructor
@NoArgsConstructor
public class WikiMessage {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 发件人用户 ID。 */
    @DbColumn(nullable = false)
    private Long fromUserId;

    /** 收件人用户 ID。 */
    @DbColumn(nullable = false)
    private Long toUserId;

    /** 内容（wikitext，原样存储，展示时渲染）。 */
    @DbColumn(type = DbType.TEXT)
    private String content = "";

    /** 收件人是否已读。 */
    @DbColumn(defaultValue = "0")
    private Boolean read = false;

    /** 阅读时间。 */
    @DbColumn
    private LocalDateTime readAt;

    /** 发件人已删除自己一侧。 */
    @DbColumn(defaultValue = "0")
    private Boolean fromDeleted = false;

    /** 收件人已删除自己一侧。 */
    @DbColumn(defaultValue = "0")
    private Boolean toDeleted = false;

    /** 发送时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
