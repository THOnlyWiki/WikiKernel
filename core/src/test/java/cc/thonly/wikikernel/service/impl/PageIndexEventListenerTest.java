package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** after-commit 监听器只允许入队：不得在请求线程做缓存失效 / 索引重建。 */
class PageIndexEventListenerTest {

    @Test
    void afterCommitOnlyEnqueues() {
        PageIndexService pageIndexService = mock(PageIndexService.class);
        PageIndexEventListener listener = new PageIndexEventListener(pageIndexService);
        WikiPageChangedEvent event = new WikiPageChangedEvent(10L, "模板:信息集合框", false);

        listener.onPageChanged(event);

        verify(pageIndexService).enqueue(event);
    }

    @Test
    void nullEventIsIgnored() {
        PageIndexService pageIndexService = mock(PageIndexService.class);
        PageIndexEventListener listener = new PageIndexEventListener(pageIndexService);

        listener.onPageChanged(null);

        verifyNoInteractions(pageIndexService);
    }
}
