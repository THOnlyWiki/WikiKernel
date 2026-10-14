package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户偏好（{@code wiki_user_preference}）：插件为自己的界面偏好使用的键值表
 * （如 Gadgets 之外的插件偏好）。core 自带的偏好字段（皮肤、语言、Gadgets 等）仍在 {@code wiki_user} 列上。
 * <p>
 * 键名建议带插件前缀（如 {@code gadget-group.expanded}），避免冲突；
 * 插件通过 {@code common.service.UserSettings} 读写，不需要自己写 SQL。
 */
@Data
@TableName("wiki_user_preference")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserPreference {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 偏好键（同一用户内唯一，建议带插件前缀）。 */
    @DbColumn(nullable = false, length = 128)
    private String prefKey;

    /** 偏好值（长文本）。 */
    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String prefValue;
}
