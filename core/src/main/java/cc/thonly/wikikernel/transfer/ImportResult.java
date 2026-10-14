package cc.thonly.wikikernel.transfer;

import java.util.List;

/**
 * 导入结果统计。
 *
 * @param pagesCreated      新建页面数
 * @param pagesUpdated      追加了版本的已有页面数
 * @param pagesSkipped      没有新增版本（完全重复）的页面数
 * @param revisionsImported 写入的版本数
 * @param revisionsSkipped  判定为重复而跳过的版本数
 * @param errors            逐页错误（标题 + 原因）
 * @param truncated         是否因限额截断
 */
public record ImportResult(int pagesCreated,
                           int pagesUpdated,
                           int pagesSkipped,
                           int revisionsImported,
                           int revisionsSkipped,
                           List<String> errors,
                           boolean truncated) {
}
