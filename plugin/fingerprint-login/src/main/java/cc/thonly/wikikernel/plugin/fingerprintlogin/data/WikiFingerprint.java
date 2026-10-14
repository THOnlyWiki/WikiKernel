package cc.thonly.wikikernel.plugin.fingerprintlogin.data;

import cc.thonly.wikikernel.common.data.DbColumn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 浏览器指纹（{@code wiki_fingerprint}）：插件 fingerprint-login 的「便利凭据」。
 * <p>
 * 一个用户可以登记多个指纹（如笔记本 / 台式机 / 手机），每个指纹归属唯一用户：
 * {@link #fingerprintHash} 全局唯一，登录时按它反查用户。
 * <p>
 * {@code fingerprintHash} 本身就是浏览器端 {@code crypto.subtle.digest('SHA-256')} 的摘要
 * （64 位十六进制），**直接存**（不再二次哈希，也不做加密）：它不是密码，是设备标识。
 * 风险（共享浏览器、指纹可被复制）见插件 README 与站点设置说明。
 * <p>
 * 表 {@code wiki_fingerprint} **属于插件自己**：插件在
 * {@code FingerprintLoginPlugin.schemaEntities()} 里声明本实体，core 的 Auto-DDL 在建表 / 补列，
 * 数据访问走 {@code common.service.PluginDatabase}（见 {@code service/FingerprintStoreJdbc}）。
 * 实体字段与迁移前的 {@code common.data.WikiFingerprint} 完全一致（列名 / 列类型不变）。
 */
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

    /**
     * 指纹摘要（浏览器端 SHA-256 的十六进制，64 字符；留 128 的余量兼容其它算法）：
     * 全局唯一，登录时按它定位用户。
     */
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
