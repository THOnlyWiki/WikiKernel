package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.service.PageLinkStore;
import cc.thonly.wikikernel.service.PageLinkService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 页面链接索引桥：把 core 的 {@code PageLinkService}（链接索引）暴露给插件，
 * 只回传标题（插件不需要页面实体）。
 */
@Service
public class PageLinkStoreImpl implements PageLinkStore {

    private final PageLinkService pageLinkService;

    public PageLinkStoreImpl(PageLinkService pageLinkService) {
        this.pageLinkService = pageLinkService;
    }

    @Override
    public List<String> templatesUsedBy(String pageTitle) {
        return pageLinkService.templatesUsedBy(pageTitle);
    }

    @Override
    public List<String> pagesLinkingTo(String title, int limit) {
        return titles(pageLinkService.pagesLinkingTo(title, limit));
    }

    @Override
    public List<String> pagesTranscluding(String templateTitle, int limit) {
        return titles(pageLinkService.pagesTranscluding(templateTitle, limit));
    }

    private static List<String> titles(List<WikiPage> pages) {
        return pages == null ? List.of() : pages.stream().map(WikiPage::getTitle).toList();
    }
}
