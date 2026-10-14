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
 * 用户留言板留言（{@code wiki_user_board}，仿 MediaWiki SocialProfile 的 {@code user_board} 表）：
 * 一个用户在自己的用户页 / 留言板页上收到的公开留言，可以是对某条留言的回复（{@code parentId}）。
 * <p>
 * 与私信（{@link WikiMessage}）的区别：留言是**公开**的（任何人都能看），私信只有收发双方能看；
 * 留言的删除是给收件人 / 发件人 / 管理员看的软删除（{@code deleted}），列表里保留占位。
 */
@Data
@TableName("wiki_user_board")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserBoard {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 收件人（留言板主人）的用户 ID。 */
    @DbColumn(nullable = false)
    private Long toUserId;

    /** 发件人（留言者）的用户 ID。 */
    @DbColumn(nullable = false)
    private Long fromUserId;

    /** 留言正文（wikitext，原样存储、展示时渲染；与私信同一套规则）。 */
    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String content = "";

    /** 父留言 ID（回复某条留言时非空；顶层留言为空）。 */
    @DbColumn
    private Long parentId;

    /** 是否被软删除（删除后仍占位，正文不再显示）。 */
    @DbColumn(defaultValue = "0")
    private Boolean deleted = false;

    /** 留言时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
