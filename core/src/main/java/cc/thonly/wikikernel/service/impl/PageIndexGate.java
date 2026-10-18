package cc.thonly.wikikernel.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 页面索引异步化的「读写闸门」：
 * <ul>
 *     <li>保存请求只把页面变更任务放进后台队列（{@link #begin()}），立刻返回；</li>
 *     <li>索引读取（分类成员 / 模板反查 / 搜索 / 页面属性）在返回前等待<b>本页索引</b>队列清空，
 *     因此「保存后立刻读自己」仍是保存后的最新索引；</li>
 *     <li>模板依赖页重算（分类重建 / 正文缓存失效与预热）走另一条后台队列，
 *     由 {@link #beginBackground()} 单独计数，读取路径不会等待它；</li>
 *     <li>后台工作线程自己不等待，避免「重建模板索引时读取模板反查」造成自锁。</li>
 * </ul>
 * 两类等待都有上限：超时后只记日志并放行。一次模板编辑最多让前台读取多等本页索引
 * （通常远小于一秒），而不是整个依赖页队列（最多 200 页渲染）。
 */
@Component
public class PageIndexGate {

    private static final Logger log = LoggerFactory.getLogger(PageIndexGate.class);

    /** 读取索引时最多等待多久：只覆盖「保存页自身」的索引，正常远小于该值。 */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(3);

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger backgroundPending = new AtomicInteger();
    private final Object monitor = new Object();
    private final Object backgroundMonitor = new Object();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    void bindWorker(Thread worker) {
        if (worker != null) {
            this.workers.add(worker);
        }
    }

    /** 后台队列接收了一个「本页索引」任务。 */
    void begin() {
        pending.incrementAndGet();
    }

    /** 「本页索引」任务处理完成（无论成功失败）。 */
    void done() {
        synchronized (monitor) {
            if (pending.decrementAndGet() <= 0) {
                monitor.notifyAll();
            }
        }
    }

    /** 后台队列接收了一个「模板依赖页 / 其它慢任务」任务。 */
    void beginBackground() {
        backgroundPending.incrementAndGet();
    }

    /** 「模板依赖页 / 其它慢任务」处理完成（无论成功失败）。 */
    void doneBackground() {
        synchronized (backgroundMonitor) {
            if (backgroundPending.decrementAndGet() <= 0) {
                backgroundMonitor.notifyAll();
            }
        }
    }

    /** 当前线程是否是索引工作线程（工作线程内读取索引不能再次等待自己）。 */
    public boolean onWorkerThread() {
        return this.workers.contains(Thread.currentThread());
    }

    /**
     * 等所有已排队的「本页索引」任务处理完；后台工作线程调用时立即返回。
     * 用于需要「保存后立刻读到新索引」的查询路径。
     * <p>
     * 注意：这里<b>不</b>等待模板依赖页重算，避免一次模板编辑把普通页面读取拖住。
     */
    public void awaitIdle() {
        awaitQuietly(pending, monitor, WAIT_TIMEOUT, "本页索引");
    }

    /**
     * 等模板依赖页 / 慢任务处理完。仅用于测试、诊断与需要最终一致的显式等待，
     * 普通读取路径不要调用。
     */
    public void awaitBackgroundIdle() {
        awaitQuietly(backgroundPending, backgroundMonitor, Duration.ofSeconds(30), "后台依赖索引");
    }

    private void awaitQuietly(AtomicInteger counter, Object waitMonitor,
                              Duration timeout, String what) {
        if (onWorkerThread() || counter.get() == 0) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (waitMonitor) {
            while (counter.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn("等待{}后台任务超时（仍有 {} 个任务），先放行本次操作",
                            what, counter.get());
                    return;
                }
                try {
                    waitMonitor.wait(Math.max(1L, Math.min(remaining / 1_000_000L, 1_000L)));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 诊断 / 测试用：尚未完成的「本页索引」任务数。 */
    public int pendingCount() {
        return pending.get();
    }

    /** 诊断 / 测试用：尚未完成的模板依赖页 / 慢任务数。 */
    public int backgroundPendingCount() {
        return backgroundPending.get();
    }
}
