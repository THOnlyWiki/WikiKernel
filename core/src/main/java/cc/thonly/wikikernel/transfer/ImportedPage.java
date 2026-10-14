package cc.thonly.wikikernel.transfer;

import java.util.List;

/**
 * 待导入的页面（各格式解析后的统一中间模型）。
 *
 * @param title         标题（含命名空间前缀，如 {@code 模板:X}）
 * @param protectedPage 是否受保护（仅 WikiKernel 格式提供）
 * @param hided         是否隐藏（仅 WikiKernel 格式提供）
 * @param deleted       是否已删除（仅 WikiKernel 格式提供）
 * @param revisions     版本（按文件中的顺序；导入时按时间升序应用）
 */
public record ImportedPage(String title,
                           boolean protectedPage,
                           boolean hided,
                           boolean deleted,
                           List<ImportedRevision> revisions) {
}
