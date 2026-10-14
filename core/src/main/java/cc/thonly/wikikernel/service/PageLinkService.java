package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;

import java.util.List;

/**
 * 页面链接查询：
 * 链入页面基于最新可见版本正文实时扫描；模板反查走 {@code wiki_page_template} 索引
 * （保存 / 删除时实时维护，查询时再对最新正文实时扫描核对）；模板正查扫描该页源码。
 * 分类索引见 {@link PageCategoryService}。
 */
public interface PageLinkService {

    /** 最新可见版本正文里包含 {@code [[title]]} 链接的页面（按标题排序）。 */
    List<WikiPage> pagesLinkingTo(String title, int limit);

    /** 哪些页面**嵌入了**该文件（{@code [[文件:<名>]]} / {@code [[File:<名>]]}），按标题升序；文件页的「文件用途」用。 */
    List<WikiPage> pagesUsingFile(String fileName, int limit);

    /**
     * 使用了该模板的页面（反查：{@code {{模板:X}}} / {@code {{X}}} 等，按标题排序）。
     * 数据来自模板使用索引（实时维护）+ 查询时实时扫描核对。
     */
    List<WikiPage> pagesTranscluding(String templateTitle, int limit);

    /** 页面使用的模板（规范标题，保持出现顺序；含不存在但被调用的模板）。 */
    List<String> templatesUsedBy(String pageTitle);
}
