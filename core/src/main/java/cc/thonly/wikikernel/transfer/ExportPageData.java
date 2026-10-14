package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;

import java.util.List;

/**
 * 单个待导出页面：页面身份 + 已按需收集的版本（按版本 ID 升序；含已删除版本时由序列化器决定是否输出）。
 */
public record ExportPageData(WikiPage page, List<WikiRevision> revisions) {
}
