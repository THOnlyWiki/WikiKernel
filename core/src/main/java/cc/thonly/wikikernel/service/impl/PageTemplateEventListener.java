package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageTemplateService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 模板使用索引维护。
 * <p>
 * 由 {@link PageIndexService} 在后台索引线程调用；不再直接监听页面事件，
 * 避免编辑请求同步扫描 / 写索引。
 */
@Component
public class PageTemplateEventListener {

    private final PageTemplateService pageTemplateService;

    public PageTemplateEventListener(PageTemplateService pageTemplateService) {
        this.pageTemplateService = pageTemplateService;
    }

    public void onPageChanged(WikiPageChangedEvent event) {
        if (event.deleted()) {
            pageTemplateService.clear(event.pageId());
            return;
        }
        pageTemplateService.reindex(event.pageId());
    }
}
