package cc.thonly.wikikernel.common.service;

/**
 * 分类的直接成员（CategoryTree 等扩展用）。
 *
 * @param pageId      成员页面 ID
 * @param title       成员标题（含命名空间，如 {@code 分类:子分类} / {@code 页面} / {@code 文件:X.png}）
 * @param sortKey     分类排序键（{@code [[分类:X|键]]}，可空）
 * @param namespaceId MediaWiki 风格的命名空间编号（主命名空间 0、文件 6、分类 14…）
 */
public record CategoryMember(long pageId, String title, String sortKey, int namespaceId) {

    /** 是否子分类（分类命名空间）。 */
    public boolean subcategory() {
        return namespaceId == 14;
    }

    /** 是否文件。 */
    public boolean file() {
        return namespaceId == 6;
    }

    /** 是否内容页面（主命名空间）。 */
    public boolean contentPage() {
        return namespaceId == 0;
    }
}
