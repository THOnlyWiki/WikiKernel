package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 上传文件（{@code wiki_file}）：元信息 + 磁盘存储名。
 * <p>
 * 文件内容存在 {@code wikikernel.run-path} 配置的目录（默认 {@code ./run/files}）下，
 * 以 {@link #storedName} 命名（时间戳 + 随机串 + 扩展名，避免中文 / 特殊字符进 URL）。
 * 访问权限按 {@code 文件:<name>} 判定（上传 = {@code upload} 动作，查看 = {@code read} 动作）。
 */
@Data
@TableName("wiki_file")
@AllArgsConstructor
@NoArgsConstructor
public class WikiFile {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 展示 / 引用用文件名（唯一），如 {@code 示例.png}；wikitext 里 [[文件:示例.png]] 用它。 */
    @DbColumn(nullable = false, unique = true)
    private String name;

    /** 磁盘存储名（唯一，ASCII 安全），如 {@code 1760000000000-ab12cd34.png}。 */
    @DbColumn(nullable = false, unique = true)
    private String storedName;

    /** MIME 类型（按扩展名推断，不信任客户端）。 */
    @DbColumn(nullable = false, length = 128)
    private String contentType;

    /** 字节数。 */
    @DbColumn(nullable = false)
    private Long size;

    /** 原图像素宽（非图片 / 未知为空）。 */
    @DbColumn
    private Integer width;

    /** 原图像素高（非图片 / 未知为空）。 */
    @DbColumn
    private Integer height;

    /** 上传者用户 ID（匿名上传时为空）。 */
    @DbColumn
    private Long uploaderId;

    /** 文件说明（可空）。 */
    @DbColumn(length = 500)
    private String description;

    /** 上传时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createdAt;

    /** 当前版本号（从 1 开始；上传新版本 / 恢复旧版本时 +1，旧版本进 {@code wiki_file_version}）。 */
    @DbColumn(nullable = false, defaultValue = "1")
    private Integer version = 1;

    /** 是否已删除（软删除：文件与全部历史版本保留，可在「特殊:已删除文件」恢复）。 */
    @DbColumn(defaultValue = "0")
    private Boolean deleted = false;

    /** 删除者用户 ID。 */
    @DbColumn
    private Long deletedBy;

    /** 删除时间。 */
    @DbColumn
    private LocalDateTime deletedAt;
}
