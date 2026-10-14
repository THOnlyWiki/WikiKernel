package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;

import java.util.List;

/**
 * 模板使用索引（{@code wiki_page_template}）：
 * <ul>
 *     <li><b>实时维护</b>：页面保存 / 删除时同步重建该页的行（事件驱动，和分类索引同一套机制），启动时全量回填；</li>
 *     <li><b>查询仍做实时扫描</b>：反查时用索引找候选页面，再核对每个候选页面的最新版本正文，
 *     索引里的过期行不会出现在结果里（模板调用是源码级直接使用，不做模板展开）；</li>
 *     <li>正查（某页用了哪些模板）见 {@link PageLinkService#templatesUsedBy}（直接扫描该页源码）。</li>
 * </ul>
 */
public interface PageTemplateService {

    /** 重建某页的模板使用索引（按最新可见版本正文；页面不存在 / 已删除则清空）。 */
    void reindex(long pageId);

    /** 按标题重建某页的模板使用索引。 */
    void reindex(String title);

    /** 清空某页的模板使用索引（页面删除）。 */
    void clear(long pageId);

    /**
     * 使用了该模板的页面（反查）：索引找候选 → 实时扫描最新版本核实；
     * {@code {{模板:X}}} / {@code {{Template:X}}} / {@code {{X}}} 写法都能命中，按标题排序。
     * 权限过滤由调用方（如 {@code 特殊:链入页面}）负责。
     */
    List<WikiPage> pagesUsing(String templateTitle, int limit);
}
