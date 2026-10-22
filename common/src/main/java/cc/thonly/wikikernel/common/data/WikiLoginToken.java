package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 持久登录令牌（{@code wiki_login_token}）：勾选「记住登录状态」时签发，
 * 浏览器 Cookie 里保存 {@code selector:validator}，数据库只存 selector 与 validator 的 SHA-256。
 * <p>
 * 服务重启只丢内存 Session，令牌在数据库里，因此重新打开页面会自动恢复登录。
 * {@link #expiresAt} 为空表示旧版永不过期令牌；默认有效期为 30 天
 * （可用 {@code wikikernel.auth.token-expire-days} 配置）。
 */
@Data
@TableName("wiki_login_token")
@AllArgsConstructor
@NoArgsConstructor
public class WikiLoginToken {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 公开选择符（随机 hex，唯一）：用于按 Cookie 快速定位令牌。 */
    @DbColumn(nullable = false, unique = true, length = 64)
    private String selector;

    /** 校验器哈希（SHA-256 hex）：数据库泄露也无法直接使用。 */
    @DbColumn(nullable = false, length = 64)
    private String validatorHash;

    /** 令牌所属用户。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 签发时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createdAt;

    /** 最近一次使用时间。 */
    @DbColumn
    private LocalDateTime lastUsedAt;

    /** 过期时间；空 = 永不过期。 */
    @DbColumn
    private LocalDateTime expiresAt;
}
