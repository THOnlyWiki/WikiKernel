package cc.thonly.wikikernel.common.service;

import java.util.List;

/**
 * 页面链接索引桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 读取 core 维护的链接索引（页面保存时重建）——某页用到的模板、链入某页的页面、嵌入某页的页面。
 */
public interface PageLinkStore {

    /**
     * 某页正文里用到的模板标题（规范标题，如 {@code 模板:信息框}；按出现顺序）。
     *
     * @param pageTitle 页面标题
     */
    List<String> templatesUsedBy(String pageTitle);

    /**
     * 链入某页的页面标题（最多 limit 条）。
     *
     * @param title 目标页面标题
     * @param limit 上限（&lt;= 0 = 不限制）
     */
    List<String> pagesLinkingTo(String title, int limit);

    /**
     * 嵌入（transclude）某页的页面标题（最多 limit 条），例如模板被哪些页面使用。
     *
     * @param templateTitle 被嵌入页面的标题
     * @param limit         上限（&lt;= 0 = 不限制）
     */
    List<String> pagesTranscluding(String templateTitle, int limit);
}
