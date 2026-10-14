package cc.thonly.wikikernel.plugin.socialprofile.data;

import cc.thonly.wikikernel.common.data.DbColumn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 授予用户的奖励 / 勋章（{@code wiki_user_award}，仿 MediaWiki SocialProfile 的 {@code user_award} 表）：
 * 奖励种类来自插件内置清单（名称走消息键 {@code socialProfile.award.<id>}），由管理员（或有权限者）授予 / 撤销。
 */
@Data
@TableName("wiki_user_award")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserAward {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 获奖用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 奖励种类 id（内置清单里的稳定 id，如 {@code barnstar}）。 */
    @DbColumn(nullable = false, length = 32)
    private String awardId;

    /** 授予说明（纯文本，展示时转义）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String comment = "";

    /** 授予人（管理员）用户 ID。 */
    @DbColumn(nullable = false)
    private Long grantedBy;

    /** 授予时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
