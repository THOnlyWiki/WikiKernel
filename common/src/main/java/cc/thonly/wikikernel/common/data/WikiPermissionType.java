package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限类型（用户组）：用户 / 自动确定用户 / 管理员 / 行政员。
 * <p>
 * 内置类型由启动时的 {@code PermissionTypeSeeder} 保证存在，也可以自行往表里加新组。
 */
@Data
@TableName("wiki_permission_type")
@AllArgsConstructor
@NoArgsConstructor
public class WikiPermissionType {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 权限名（同时作为展示名）。 */
    @DbColumn(nullable = false, unique = true)
    private String name;

}
