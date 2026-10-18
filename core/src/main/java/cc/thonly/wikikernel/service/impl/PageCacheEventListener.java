package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 页面正文缓存失效（由 {@link PageIndexService} 在后台线程调用）。
 * <ul>
 *     <li>页面创建 / 编辑 / 删除 / 移动：失效该页缓存（条目本身带版本号，双保险）；</li>
 *     <li>模板变更：额外失效<b>所有嵌入它的页面</b>，并按配置用虚拟线程批量预渲染预热；</li>
 *     <li>移动页面时旧标题的条目不再被使用（标题下已无页面，且版本号对不上），由 TTL / 条数上限淘汰。</li>
 * </ul>
 * 所有缓存操作都在后台队列执行；保存请求线程不再做任何 Redis / 失效工作。
 * 读取缓存时会比对页面最新版本号，因此后台失效尚未执行也不会返回旧正文。
 */
@Component
public class PageCacheEventListener {

    private final PageCacheService pageCacheService;
    private final WikiPageService wikiPageService;

    public PageCacheEventListener(PageCacheService pageCacheService,
                                  WikiPageService wikiPageService) {
        this.pageCacheService = pageCacheService;
        this.wikiPageService = wikiPageService;
    }

    /** 当前页面自身的缓存失效（非模板页面只做这一件事）。 */
    public void onPageChanged(WikiPageChangedEvent event) {
        if (!this.pageCacheService.enabled()) {
            // 缓存关闭：没有条目需要失效，也不必预热
            return;
        }
        String title = event.title();
        if (Strings.isBlank(title)) {
            return;
        }
        Long revisionId = event.deleted() || event.pageId() == null
                ? null
                : this.wikiPageService.getById(event.pageId())
                        .map(WikiPage::getLatestRevisionId).orElse(null);
        // 带版本号失效：保存后页面如果已经被重新渲染进缓存（重定向后的首次查看），保留新版本条目
        this.pageCacheService.invalidate(title, revisionId);
    }

    /** 模板变更：失效所有嵌入该模板的页面并按配置预热（由后台依赖队列调用）。 */
    public void onTemplateChanged(WikiPageChangedEvent event) {
        if (!this.pageCacheService.enabled()) {
            return;
        }
        String title = event.title();
        if (Strings.isBlank(title)) {
            return;
        }
        this.pageCacheService.invalidateTranscluding(title);
    }
}
