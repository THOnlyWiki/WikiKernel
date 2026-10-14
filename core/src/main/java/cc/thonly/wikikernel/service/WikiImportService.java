package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.transfer.ImportResult;

/**
 * 页面导入（{@code 特殊:导入页面} / {@code POST /api/import}）：
 * 自动识别 MediaWiki XML / WikiKernel XML / WikiKernel JSON，按「时间 + 正文 sha1」去重后
 * 追加缺失版本（已有页面合并历史，不覆盖本地较新的版本）。
 */
public interface WikiImportService {

    /**
     * 导入文件内容。
     *
     * @param bytes             文件字节（上限由 {@code wikikernel.transfer.import-max-bytes} 控制）
     * @param targetNamespaceId 导入到哪个命名空间 id（空 = 保持原命名空间；{@code main} = 主命名空间）
     * @param importer          操作者（写导入日志）
     */
    ImportResult importFile(byte[] bytes, String targetNamespaceId, WikiUser importer);
}
