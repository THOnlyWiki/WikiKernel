package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → 页面属性持久化（仿 MediaWiki 的 page_props）。
 * <p>
 * 由 {@link PageIndexService} 在后台索引线程调用；不再阻塞保存请求。
 * 渲染时通过扩展点写入的属性（SEO 元数据、{@code templatedata}、{@code notitle} 等）
 * 覆盖保存到 {@code wiki_page_property}；持久属性（如浏览计数）不受影响。
 */
@Component
public class PagePropertyEventListener {

    private final WikiPageService wikiPageService;
    private final WikitextRenderer wikitextRenderer;
    private final PagePropertyStore pagePropertyStore;

    public PagePropertyEventListener(WikiPageService wikiPageService,
                                     WikitextRenderer wikitextRenderer,
                                     PagePropertyStore pagePropertyStore) {
        this.wikiPageService = wikiPageService;
        this.wikitextRenderer = wikitextRenderer;
        this.pagePropertyStore = pagePropertyStore;
    }

    public void onPageChanged(WikiPageChangedEvent event) {
        if (event.deleted()) {
            pagePropertyStore.removeAll(event.pageId());
            return;
        }
        String content = wikiPageService.getLatestContent(event.pageId()).orElse("");
        WikiRenderContext context = wikitextRenderer.createContext(event.title());
        wikitextRenderer.renderWithMeta(content, event.title(), context);
        pagePropertyStore.replaceRendered(event.pageId(), context.properties());
    }
}
