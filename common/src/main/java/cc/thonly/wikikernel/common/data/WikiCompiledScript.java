package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 编译产物缓存（表 {@code wiki_compiled_script}）：{@code XXX.ts} 编译出的 JavaScript。
 * <p>
 * 键是 {@code 页面 + 版本 + 目标 + 编译器版本}：页面每保存一版就会自然失效，
 * 因此永远不会把旧版本的编译结果发给浏览器。每个「页面 + 目标」只保留最新一条。
 */
@Data
@TableName("wiki_compiled_script")
@AllArgsConstructor
@NoArgsConstructor
public class WikiCompiledScript {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 源页面 id。 */
    @DbColumn(nullable = false)
    private Long pageId;

    /** 源版本 id（缓存键：版本一变即失效）。 */
    @DbColumn(nullable = false)
    private Long revisionId;

    /** 目标形态（javascript / css…）。 */
    @DbColumn(nullable = false, length = 32)
    private String target = "javascript";

    /** 编译器标识（如 typescript/5.9.3）。 */
    @DbColumn(nullable = false, length = 64)
    private String compiler = "";

    /** 编译产物（成功时非空）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String code;

    /** 诊断文本（失败时非空；成功时为空串）。 */
    @DbColumn(type = DbType.LONGTEXT)
    private String diagnostics = "";

    /** 是否编译成功。 */
    @DbColumn
    private Boolean successful = false;

    /** 编译时间。 */
    @DbColumn
    private LocalDateTime createAt = LocalDateTime.now();
}
