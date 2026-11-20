package cc.thonly.wikikernel.plugin.fingerprintlogin.data;

import cc.thonly.wikikernel.common.data.DbColumn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 已登记浏览器的凭据与管理信息，沿用 wiki_fingerprint 表。 */
@Data
@TableName("wiki_fingerprint")
@AllArgsConstructor
@NoArgsConstructor
public class WikiFingerprint {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 指纹所属用户。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 用户给这个指纹起的名字（如「我的笔记本 Chrome」），只对用户自己展示。 */
    @DbColumn(length = 100, defaultValue = "")
    private String label = "";

    /** 带版本前缀的随机凭据摘要。旧版无前缀的行保留在管理列表中。 */
    @DbColumn(nullable = false, unique = true, length = 128)
    private String fingerprintHash;

    /** 浏览器 UA 摘要（管理界面展示用；不参与匹配）。 */
    @DbColumn(length = 300, defaultValue = "")
    private String userAgent = "";

    /** 登记时间。 */
    @DbColumn
    private LocalDateTime createdAt;

    /** 最近一次用它登录成功的时间（从未使用为空）。 */
    @DbColumn
    private LocalDateTime lastUsedAt;
}
