package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 封禁记录（仿 MediaWiki 的 block）：目标可以是用户名或 IP / IPv4 CIDR；
 * 到期时间为空 = 永久；解封后保留记录（unblockedBy / unblockedAt），封禁 / 解封都写入日志。
 */
@Data
@TableName("wiki_block")
@AllArgsConstructor
@NoArgsConstructor
public class WikiBlock {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 目标：用户名（原样）或 IP / CIDR（如 203.0.113.7、203.0.113.0/24）。 */
    @DbColumn(nullable = false, length = 255)
    private String target;

    /** 目标类型：user（用户名）/ ip（IP 或 CIDR）。 */
    @DbColumn(length = 16)
    private String targetType = "user";

    /** 执行封禁的用户 ID（系统 / 匿名为空）。 */
    @DbColumn
    private Long byUserId;

    /** 封禁原因（可空 = 无原因）。 */
    @DbColumn(length = 500, defaultValue = "")
    private String reason = "";

    /** 到期时间；为空 = 永久封禁。 */
    @DbColumn
    private LocalDateTime expiresAt;

    /** 封禁时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;

    /** 解封者用户 ID（未解封为空）。 */
    @DbColumn
    private Long unblockedBy;

    /** 解封时间（未解封为空）。 */
    @DbColumn
    private LocalDateTime unblockedAt;

    /** 禁止发送电子邮件（用户封禁）。 */
    @DbColumn(defaultValue = "0")
    private Boolean preventEmail = false;

    /** 禁止创建账号（IP 封禁；注册时检查）。 */
    @DbColumn(defaultValue = "0")
    private Boolean preventCreate = false;

    /** 仅封禁匿名用户（IP 封禁；登录用户不受影响）。 */
    @DbColumn(defaultValue = "0")
    private Boolean anonOnly = false;

    /** 当前是否生效：未解封且未到期。 */
    public boolean isActive(LocalDateTime now) {
        return unblockedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
