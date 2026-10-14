package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 分类索引维护：
 * 页面保存 / 删除后重建该页索引；模板页修改后还会重建使用它的页面（模板里的分类及时生效）。
 */
@Component
public class PageCategoryEventListener {

    private final PageCategoryService pageCategoryService;
    private final WikitextProperties wikitextProperties;

    public PageCategoryEventListener(PageCategoryService pageCategoryService,
                                     WikitextProperties wikitextProperties) {
        this.pageCategoryService = pageCategoryService;
        this.wikitextProperties = wikitextProperties;
    }

    @EventListener
    public void onPageChanged(WikiPageChangedEvent event) {
        if (event.deleted()) {
            pageCategoryService.clear(event.pageId());
            return;
        }
        pageCategoryService.reindex(event.pageId());
        if (isTemplateTitle(event.title())) {
            pageCategoryService.reindexDependents(event.title());
        }
    }

    private boolean isTemplateTitle(String title) {
        String value = Strings.trimToEmpty(title);
        for (String prefix : wikitextProperties.getTemplatePrefixes()) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }
}
