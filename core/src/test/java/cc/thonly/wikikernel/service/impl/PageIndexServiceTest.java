package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.typescript.TypeScriptCompileListener;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 页面索引异步化的关键回归：
 * <ul>
 *     <li>入队（after-commit 保存路径）不被慢索引任务拖住；</li>
 *     <li>模板依赖页重算很慢时，普通「读自己索引」的等待不被它阻塞；</li>
 *     <li>保存页自身的索引仍然会被读取路径等待（保存后读自己一致）。</li>
 * </ul>
 * 用注入的慢任务代替真实渲染，计时断言保持宽松（只验证量级差异）。
 */
class PageIndexServiceTest {

    @Test
    void enqueueReturnsImmediatelyEvenWhenIndexWorkerIsSlow() throws Exception {
        PageTemplateEventListener templateListener = mock(PageTemplateEventListener.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            awaitQuietly(release, 5_000L);
            finished.countDown();
            return null;
        }).when(templateListener).onPageChanged(any());

        PageIndexGate gate = new PageIndexGate();
        PageIndexService service = newService(gate, templateListener,
                mock(PageCategoryEventListener.class),
                mock(PagePropertyEventListener.class),
                mock(PageCacheEventListener.class),
                mock(TypeScriptCompileListener.class));
        try {
            long startedAt = System.nanoTime();
            service.enqueue(new WikiPageChangedEvent(1L, "普通保存页", false));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMs < 500L, "入队不应等待索引任务，实际耗时 " + elapsedMs + " ms");
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS), "慢索引任务应最终完成");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void ordinaryReadDoesNotWaitForSlowTemplateDependents() throws Exception {
        PageCategoryEventListener categoryListener = mock(PageCategoryEventListener.class);
        CountDownLatch dependentStarted = new CountDownLatch(1);
        CountDownLatch releaseDependent = new CountDownLatch(1);
        CountDownLatch dependentFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            dependentStarted.countDown();
            awaitQuietly(releaseDependent, 10_000L);
            dependentFinished.countDown();
            return null;
        }).when(categoryListener).onTemplateChanged(any());

        PageIndexGate gate = new PageIndexGate();
        PageIndexService service = newService(gate, mock(PageTemplateEventListener.class),
                categoryListener,
                mock(PagePropertyEventListener.class),
                mock(PageCacheEventListener.class),
                mock(TypeScriptCompileListener.class));
        try {
            service.enqueue(new WikiPageChangedEvent(2L, "模板:信息集合框", false));
            assertTrue(dependentStarted.await(5, TimeUnit.SECONDS), "依赖页任务应已开始");

            long startedAt = System.nanoTime();
            gate.awaitIdle();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMs < 1_000L,
                    "普通读取不应等待模板依赖页队列，实际耗时 " + elapsedMs + " ms");
            assertEquals(1L, dependentFinished.getCount(), "依赖页任务应仍在后台执行");
            assertEquals(0, gate.pendingCount());
            assertTrue(gate.backgroundPendingCount() > 0);
        } finally {
            releaseDependent.countDown();
            gate.awaitBackgroundIdle();
            service.shutdown();
        }
        verify(categoryListener).onTemplateChanged(any());
    }

    @Test
    void readStillWaitsForSavedPageOwnIndex() throws Exception {
        PagePropertyEventListener propertyListener = mock(PagePropertyEventListener.class);
        CountDownLatch ownStarted = new CountDownLatch(1);
        CountDownLatch releaseOwn = new CountDownLatch(1);
        doAnswer(invocation -> {
            ownStarted.countDown();
            awaitQuietly(releaseOwn, 10_000L);
            return null;
        }).when(propertyListener).onPageChanged(any());

        PageIndexGate gate = new PageIndexGate();
        PageIndexService service = newService(gate, mock(PageTemplateEventListener.class),
                mock(PageCategoryEventListener.class),
                propertyListener,
                mock(PageCacheEventListener.class),
                mock(TypeScriptCompileListener.class));
        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                if (ownStarted.await(5, TimeUnit.SECONDS)) {
                    Thread.sleep(250L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                releaseOwn.countDown();
            }
        });
        try {
            service.enqueue(new WikiPageChangedEvent(3L, "普通保存页", false));
            long startedAt = System.nanoTime();
            gate.awaitIdle();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMs >= 150L,
                    "读取应等待保存页自身的索引，实际只等了 " + elapsedMs + " ms");
        } finally {
            releaseOwn.countDown();
            releaser.join(5_000L);
            service.shutdown();
        }
    }

    // ==================== 工具 ====================

    private static PageIndexService newService(PageIndexGate gate,
                                               PageTemplateEventListener templateListener,
                                               PageCategoryEventListener categoryListener,
                                               PagePropertyEventListener propertyListener,
                                               PageCacheEventListener cacheListener,
                                               TypeScriptCompileListener typeScriptListener) {
        return new PageIndexService(gate, new WikitextProperties(),
                templateListener, categoryListener, propertyListener, cacheListener, typeScriptListener);
    }

    private static void awaitQuietly(CountDownLatch latch, long timeoutMillis) {
        try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
