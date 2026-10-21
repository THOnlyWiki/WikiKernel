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
 * 自定义礼物目录（{@code wiki_gift}，仿 MediaWiki SocialProfile 的礼物清单扩展）：
 * 内置礼物仍在 {@code GiftService.CATALOG}，这里只存用户创建的礼物。
 * <p>
 * {@code giftKey} 是 {@code wiki_user_gift.gift_id} 使用的稳定键；显示名、说明、图标可以后续改，
 * 已经送出的礼物仍能正确显示。
 */
@Data
@TableName("wiki_gift")
@AllArgsConstructor
@NoArgsConstructor
public class WikiGift {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 稳定键（{@code custom-xxxxxxxx}），写入 wiki_user_gift.gift_id。 */
    @DbColumn(nullable = false, length = 32, unique = true)
    private String giftKey;

    /** 显示名（纯文本）。 */
    @DbColumn(nullable = false, length = 80)
    private String name;

    /** 说明（纯文本）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String description = "";

    /** 图标（emoji / 单个字符，最长 16 个 UTF-16 单元）。 */
    @DbColumn(length = 16, defaultValue = "'🎁'")
    private String icon = "🎁";

    /** 创建者用户 ID。 */
    @DbColumn
    private Long createdBy;

    /** 是否启用；停用后不再出现在送礼清单，但历史记录仍可显示。 */
    @DbColumn(nullable = false, defaultValue = "TRUE")
    private Boolean enabled = Boolean.TRUE;

    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
