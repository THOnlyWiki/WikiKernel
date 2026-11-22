package cc.thonly.wikikernel.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class PageReportBackfill {
    private static final Logger log = LoggerFactory.getLogger(PageReportBackfill.class);
    private final JdbcTemplate jdbc;
    private final PageIndexService queue;
    private final PageReportIndex index;
    private final PagePropertyEventListener listener;
    private final PageCategoryServiceImpl categories;

    PageReportBackfill(JdbcTemplate jdbc, PageIndexService queue, PageReportIndex index,
                       PagePropertyEventListener listener, PageCategoryServiceImpl categories) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.index = index;
        this.listener = listener;
        this.categories = categories;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void start() {
        schedule(0, index.indexVersion());
    }

    private void schedule(long after, String version) {
        queue.enqueueBackground("page-report-backfill", () -> rebuildBatch(after, version));
    }

    private void rebuildBatch(long after, String version) {
        List<Long> pages = jdbc.queryForList("""
                SELECT p.id FROM wiki_page p WHERE p.id > ? AND p.deleted = FALSE AND p.latest_revision_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM wiki_page_report_link l
                    WHERE l.page_id = p.id AND l.revision_id = p.latest_revision_id
                      AND l.link_kind = 'version' AND l.link_target = ?)
                ORDER BY p.id LIMIT 32
                """, Long.class, after, version);
        if (pages.isEmpty()) {
            return;
        }
        for (long id : pages) {
            try {
                // Hidden category tracking also changes category membership.
                categories.reindex(id);
                listener.reindex(id);
            } catch (RuntimeException exception) {
                log.warn("Could not rebuild page-list indexes for {}", id, exception);
            }
        }
        schedule(pages.getLast(), version);
    }
}
