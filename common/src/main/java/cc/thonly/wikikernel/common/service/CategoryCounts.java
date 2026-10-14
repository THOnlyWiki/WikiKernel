package cc.thonly.wikikernel.common.service;

/**
 * 分类成员统计（对应 MediaWiki {@code category} 表的 cat_subcats / cat_pages / cat_files）。
 *
 * @param subcategories 子分类数（分类命名空间成员）
 * @param pages         内容页面数（主命名空间成员）
 * @param files         文件数（文件命名空间成员）
 * @param all           全部成员数（含其它命名空间）
 */
public record CategoryCounts(int subcategories, int pages, int files, int all) {
}
