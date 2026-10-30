package cc.thonly.wikikernel.plugin.externalauth.qq.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * QQ 邮箱验证绑定（插件自有表）。
 * <p>
 * 一行既可以是待确认记录（{@code verified_at} 为空），也可以是已绑定记录。
 * 邮箱确认链接里只保存 token 的 SHA-256，明文 token 只出现在邮件中。
 */
@Data
@TableName("wiki_qq_email_binding")
@AllArgsConstructor
@NoArgsConstructor
public class QqEmailBinding {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 本地账号 ID。 */
    @DbColumn(nullable = false, unique = true)
    private Long userId;

    /** 数字 QQ 号（不含 @qq.com）。 */
    @DbColumn(nullable = false, length = 20, unique = true)
    private String qq;

    /** 确认 token 的 SHA-256（不保存明文）。 */
    @DbColumn(nullable = false, length = 128)
    private String tokenHash;

    /** 确认链接过期时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime expiresAt;

    /** 确认成功时间；空 = 待确认。 */
    @DbColumn
    private LocalDateTime verifiedAt;

    /** 发起时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createdAt;
}
