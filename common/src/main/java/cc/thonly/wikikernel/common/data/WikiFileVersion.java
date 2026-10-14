package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件历史版本（{@code wiki_file_version}）：一行 = 一个旧版本。
 * <p>
 * 当前版本的信息仍在 {@link WikiFile}（含 {@code version} 版本号）；
 * 上传新版本 / 恢复旧版本时，原本的当前版本先归档到这里（仿 MediaWiki 的 oldimage 表）。
 */
@Data
@TableName("wiki_file_version")
@AllArgsConstructor
@NoArgsConstructor
public class WikiFileVersion {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 所属文件（{@code wiki_file.id}）。 */
    @DbColumn(nullable = false)
    private Long fileId;

    /** 版本号（当前版本号在 {@code wiki_file.version}）。 */
    @DbColumn(nullable = false)
    private Integer version;

    /** 磁盘存储名（ASCII 安全），通过 {@code /files/<storedName>} 访问。 */
    @DbColumn(nullable = false)
    private String storedName;

    /** MIME 类型（按扩展名推断）。 */
    @DbColumn(nullable = false, length = 128)
    private String contentType;

    /** 字节数。 */
    @DbColumn(nullable = false)
    private Long size;

    /** 上传者用户 ID（匿名上传时为空）。 */
    @DbColumn
    private Long uploaderId;

    /** 版本说明（上传 / 恢复时填写的说明）。 */
    @DbColumn(length = 500)
    private String comment;

    /** 上传时间。 */
    @DbColumn(nullable = false)
    private LocalDateTime createdAt;
}
