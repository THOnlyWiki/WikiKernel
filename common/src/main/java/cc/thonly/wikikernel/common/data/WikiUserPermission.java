package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户 ↔ 权限类型 的关联（一个用户可有多条）。
 */
@Data
@TableName("wiki_user_permission")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserPermission {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 用户 ID（wiki_user.id）。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 权限类型 ID（wiki_permission_type.id）。 */
    @DbColumn(nullable = false)
    private Long permissionType;

    /**
     * 权限有效期（null = 永久；到点后权限自动失效）。
     * 记录会保留（权限页显示「已过期」），重新授予即可再次生效。
     */
    @DbColumn
    private LocalDateTime expiresAt;

}
