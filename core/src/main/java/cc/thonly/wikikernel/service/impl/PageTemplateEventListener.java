package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageTemplateService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 模板使用索引维护（实时）：
 * 页面保存 / 删除后立即重建该页的模板索引行（与分类索引同一套事件机制）。
 * 模板自身内容修改不需要重建依赖页：反查只关心「谁直接调用了模板」，和使用者的源码绑定。
 */
@Component
public class PageTemplateEventListener {

    private final PageTemplateService pageTemplateService;

    public PageTemplateEventListener(PageTemplateService pageTemplateService) {
        this.pageTemplateService = pageTemplateService;
    }

    @EventListener
    public void onPageChanged(WikiPageChangedEvent event) {
        if (event.deleted()) {
            pageTemplateService.clear(event.pageId());
            return;
        }
        pageTemplateService.reindex(event.pageId());
    }
}
