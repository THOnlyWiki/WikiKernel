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
 * 用户之间赠送的礼物（{@code wiki_user_gift}，仿 MediaWiki SocialProfile 的 {@code user_gift} 表）：
 * 送出的礼物是**记录**，礼物种类来自插件内置清单（名称走消息键 {@code socialProfile.gift.<id>}，
 * 不落库名称，便于换语言 / 改名）。
 */
@Data
@TableName("wiki_user_gift")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserGift {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 送礼人用户 ID。 */
    @DbColumn(nullable = false)
    private Long fromUserId;

    /** 收礼人用户 ID。 */
    @DbColumn(nullable = false)
    private Long toUserId;

    /** 礼物种类 id（内置清单里的稳定 id，如 {@code star}）。 */
    @DbColumn(nullable = false, length = 32)
    private String giftId;

    /** 附言（纯文本，展示时转义；不按 wikitext 渲染）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String message = "";

    /** 赠送时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
