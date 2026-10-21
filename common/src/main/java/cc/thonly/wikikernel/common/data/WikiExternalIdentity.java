package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 第三方账号绑定（{@code wiki_external_identity}）。
 * <p>
 * 一个本地账号可以绑定多个第三方 provider；同一个 provider + subject 只能绑定一个本地账号。
 * {@link #bindingKey} 是 provider + subject 的 SHA-256 键，用于唯一约束和快速查询，
 * 避免直接在 255 字符的 subject 上建唯一索引。
 */
@Data
@TableName("wiki_external_identity")
@AllArgsConstructor
@NoArgsConstructor
public class WikiExternalIdentity {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 本地用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** provider id，如 github / qq。 */
    @DbColumn(nullable = false, length = 32)
    private String provider;

    /** provider 侧稳定用户标识（GitHub 数字 id / QQ openid）。 */
    @DbColumn(nullable = false, length = 255)
    private String subject;

    /** provider + subject 的不可逆查询键；唯一约束防止重复绑定。 */
    @DbColumn(nullable = false, unique = true, length = 128)
    private String bindingKey;

    /** provider 侧用户名（GitHub login / QQ 昵称）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String username = "";

    /** provider 返回且已验证的邮箱（QQ 通常为空）。 */
    @DbColumn(length = 255, defaultValue = "")
    private String email = "";

    /** provider 头像 URL（可空）。 */
    @DbColumn(length = 1024, defaultValue = "")
    private String avatar = "";

    /** 绑定时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime linkedAt;

    /** 最近一次通过该第三方账号登录的时间。 */
    @DbColumn
    private LocalDateTime lastLoginAt;
}
