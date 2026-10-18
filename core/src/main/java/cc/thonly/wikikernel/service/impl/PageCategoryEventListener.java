package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 分类索引维护。
 * <p>
 * 由 {@link PageIndexService} 在后台索引线程调用：
 * <ul>
 *     <li>{@link #onPageChanged} 只重建 / 清空保存页自己的分类索引（本页索引队列，读取会做有界等待）；</li>
 *     <li>{@link #onTemplateChanged} 重建模板嵌入页的分类索引（后台依赖队列，读取不等待）。</li>
 * </ul>
 */
@Component
public class PageCategoryEventListener {

    private final PageCategoryService pageCategoryService;

    public PageCategoryEventListener(PageCategoryService pageCategoryService) {
        this.pageCategoryService = pageCategoryService;
    }

    public void onPageChanged(WikiPageChangedEvent event) {
        if (event.deleted()) {
            pageCategoryService.clear(event.pageId());
            return;
        }
        pageCategoryService.reindex(event.pageId());
    }

    /** 模板修改 / 删除后，重建使用它的页面分类（任务已与保存请求解耦）。 */
    public void onTemplateChanged(WikiPageChangedEvent event) {
        pageCategoryService.reindexDependents(event.title());
    }
}
