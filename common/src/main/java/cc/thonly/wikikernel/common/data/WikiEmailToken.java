package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邮件令牌（{@code wiki_email_token}）：邮箱确认 / 密码重置等邮件里的一次性链接。
 * <p>
 * 存 SHA-256 摘要而不是明文（数据库泄露也无法直接使用）；
 * 同用户同类型只保留最新一枚（重新发送会先撤销旧的）。
 */
@Data
@TableName("wiki_email_token")
@AllArgsConstructor
@NoArgsConstructor
public class WikiEmailToken {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 目标用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 邮件类型 id（如 confirm-email），见 common 的 {@code EmailType}。 */
    @DbColumn(nullable = false, length = 64)
    private String type;

    /** 签发时的目标邮箱（用于校验邮箱是否被改过）。 */
    @DbColumn(nullable = false)
    private String email;

    /** 令牌摘要（SHA-256 hex，唯一）。 */
    @DbColumn(nullable = false, length = 128, unique = true)
    private String tokenHash;

    /** 签发时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createdAt;

    /** 过期时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime expiresAt;
}
