package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;

import java.util.List;

/**
 * 模板使用索引（{@code wiki_page_template}）：
 * <ul>
 *     <li><b>异步维护</b>：页面保存 / 删除后由后台索引线程重建该页的行，保存请求立即返回；</li>
 *     <li><b>查询等待后台队列</b>：反查时先等索引任务清空，再用索引找候选页面并核对最新正文，
 *     保存后立刻查询仍是新结果；模板调用是源码级直接使用（不做模板展开）；</li>
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
