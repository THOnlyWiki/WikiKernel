package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Component;

/** Updates the parser-derived report data and page properties together. */
@Component
public class PagePropertyEventListener {
    private final WikiPageMapper pages;
    private final WikiPageService revisions;
    private final WikitextRenderer renderer;
    private final PageReportIndex index;

    PagePropertyEventListener(WikiPageMapper pages, WikiPageService revisions,
                              WikitextRenderer renderer, PageReportIndex index) {
        this.pages = pages;
        this.revisions = revisions;
        this.renderer = renderer;
        this.index = index;
    }

    public void onPageChanged(WikiPageChangedEvent event) {
        reindex(event.pageId());
    }

    // The regular queue, template changes and upgrade backfill can reach the same page.
    synchronized void reindex(long pageId) {
        WikiPage page = pages.selectById(pageId);
        if (page == null || Boolean.TRUE.equals(page.getDeleted())) {
            index.clear(pageId);
            return;
        }
        revisions.getRevision(page.getLatestRevisionId()).ifPresent(revision -> {
            if (Boolean.TRUE.equals(revision.getDeleted())) {
                index.clear(pageId);
                return;
            }
            String version = index.indexVersion();
            String title = page.getTitle();
            String content = revision.getContent() == null ? "" : revision.getContent();
            var context = renderer.createContext(title);
            var rendered = renderer.renderWithMeta(content, title, context);
            index.replace(pageId, revision.getId(), title, content, rendered.html(), context.properties(), version);
        });
    }
}
