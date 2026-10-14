package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 站点设置（{@code wiki_setting}）：键值型站点开关，可在「特殊:权限配置」页修改，
 * 也可在 LocalSettings.yaml 里给默认值（启动时导入）。
 * <p>
 * 键名约定见 {@code SiteSettingsService} 常量（如 {@code permissions.allow-other-user-page-edit}）。
 */
@Data
@TableName("wiki_setting")
@AllArgsConstructor
@NoArgsConstructor
public class WikiSetting {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 设置键（唯一）。 */
    @DbColumn(nullable = false, unique = true, length = 64)
    private String settingKey;

    /** 设置值（字符串；布尔用 true/false）。 */
    @DbColumn(type = DbType.TEXT, defaultValue = "")
    private String settingValue;
}
