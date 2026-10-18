package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.typescript.TypeScriptCompileListener;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 页面派生索引的异步执行器，分两条队列：
 * <ul>
 *     <li><b>本页索引</b>（{@code page-index-worker}）：模板使用索引 / 分类索引 / 页面属性，
 *     同一页面只保留最新事件。这些任务由 {@link PageIndexGate} 计数，读取路径会做有界等待，
 *     保证「保存后读自己」看到新索引；</li>
 *     <li><b>后台依赖</b>（{@code page-index-background}）：模板被修改后对其嵌入页面的分类重建、
 *     正文缓存失效与预热，以及 TypeScript 编译等可能很慢、但读取不需要立即看到结果的任务。
 *     同一模板（或同一页面的慢任务）只保留最新一次，读取路径<b>不会</b>等待这条队列。</li>
 * </ul>
 * 两条队列各自单线程串行：模板依赖页重算不会占住本页索引队列，也不会出现多个线程
 * 同时写同一张派生索引表；配合各索引服务的短事务，避免长时间占用 SQLite 的单写连接。
 */
@Service
public class PageIndexService {

    private static final Logger log = LoggerFactory.getLogger(PageIndexService.class);

    private final PageIndexGate gate;
    private final WikitextProperties wikitextProperties;
    private final PageTemplateEventListener templateListener;
    private final PageCategoryEventListener categoryListener;
    private final PagePropertyEventListener propertyListener;
    private final PageCacheEventListener cacheListener;
    private final TypeScriptCompileListener typeScriptListener;

    /** 本页索引队列：按 pageId 合并，只保留最新事件。 */
    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final Map<Long, WikiPageChangedEvent> pending = new ConcurrentHashMap<>();
    private final Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    /** 后台依赖队列：按 key（模板标题 / 页面 id + 任务类型）合并。 */
    private final BlockingQueue<String> backgroundQueue = new LinkedBlockingQueue<>();
    private final Map<String, Runnable> backgroundTasks = new ConcurrentHashMap<>();
    private final Set<String> backgroundScheduled = ConcurrentHashMap.newKeySet();

    private final Thread worker;
    private final Thread backgroundWorker;
    private volatile boolean running = true;

    public PageIndexService(PageIndexGate gate,
                            WikitextProperties wikitextProperties,
                            PageTemplateEventListener templateListener,
                            PageCategoryEventListener categoryListener,
                            PagePropertyEventListener propertyListener,
                            PageCacheEventListener cacheListener,
                            TypeScriptCompileListener typeScriptListener) {
        this.gate = gate;
        this.wikitextProperties = wikitextProperties;
        this.templateListener = templateListener;
        this.categoryListener = categoryListener;
        this.propertyListener = propertyListener;
        this.cacheListener = cacheListener;
        this.typeScriptListener = typeScriptListener;
        this.worker = Thread.ofVirtual().name("page-index-worker").start(this::runLoop);
        this.backgroundWorker = Thread.ofVirtual().name("page-index-background")
                .start(this::runBackgroundLoop);
        this.gate.bindWorker(this.worker);
        this.gate.bindWorker(this.backgroundWorker);
    }

    /**
     * 入队一个页面变更事件。同一页面只保留最新事件；方法本身不做任何数据库工作，
     * 也不等待任何索引重建，保存请求可以立即返回。
     */
    public void enqueue(WikiPageChangedEvent event) {
        if (event == null || event.pageId() == null) {
            return;
        }
        pending.put(event.pageId(), event);
        if (scheduled.add(event.pageId())) {
            gate.begin();
            queue.offer(event.pageId());
        }
    }

    private void runLoop() {
        while (running) {
            Long pageId;
            try {
                pageId = queue.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            scheduled.remove(pageId);
            WikiPageChangedEvent event = pending.remove(pageId);
            long startedAt = System.nanoTime();
            try {
                if (event != null) {
                    process(event);
                }
            } catch (RuntimeException exception) {
                log.warn("页面索引后台任务失败：{}", event == null ? pageId : event.title(), exception);
            } finally {
                gate.done();
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                if (elapsedMs > 2_000) {
                    log.info("页面索引后台任务耗时 {} ms：{}", elapsedMs,
                            event == null ? pageId : event.title());
                }
            }
        }
    }

    /** 只处理保存页自身的派生索引；模板依赖页 / 缓存 / TS 编译交给后台队列。 */
    private void process(WikiPageChangedEvent event) {
        boolean template = isTemplate(event.title());
        // 模板索引必须最先更新：后面的依赖页分类 / 缓存失效都通过它反查
        step("template", () -> templateListener.onPageChanged(event));
        step("category", () -> categoryListener.onPageChanged(event));
        step("property", () -> propertyListener.onPageChanged(event));
        scheduleCacheInvalidation(event);
        if (template) {
            scheduleTemplateDependents(event);
        }
        if (!event.deleted()) {
            scheduleTypeScriptCompile(event);
        }
    }

    /** 当前页面自身的缓存清理：纯后台操作，保存后读取靠版本号保证不会拿到旧正文。 */
    private void scheduleCacheInvalidation(WikiPageChangedEvent event) {
        enqueueBackground("cache:" + event.pageId(),
                () -> cacheListener.onPageChanged(event));
    }

    /**
     * 模板变更后的依赖页重算：分类索引重建 + 正文缓存失效/预热。
     * 按模板标题合并；正在执行时又有新编辑，会在队列里再排一次（读到的是最新正文）。
     */
    private void scheduleTemplateDependents(WikiPageChangedEvent event) {
        enqueueBackground("template:" + event.title(), () -> {
            step("dependent-cache", () -> cacheListener.onTemplateChanged(event));
            step("dependent-category", () -> categoryListener.onTemplateChanged(event));
        });
    }

    /** TypeScript 编译可能很慢，且页面读取不依赖它，放到后台依赖队列。 */
    private void scheduleTypeScriptCompile(WikiPageChangedEvent event) {
        enqueueBackground("typescript:" + event.pageId(),
                () -> typeScriptListener.onPageChanged(event));
    }

    private void enqueueBackground(String key, Runnable task) {
        if (key == null || task == null) {
            return;
        }
        backgroundTasks.put(key, task);
        if (backgroundScheduled.add(key)) {
            gate.beginBackground();
            backgroundQueue.offer(key);
        }
    }

    private void runBackgroundLoop() {
        while (running) {
            String key;
            try {
                key = backgroundQueue.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            backgroundScheduled.remove(key);
            Runnable task = backgroundTasks.remove(key);
            long startedAt = System.nanoTime();
            try {
                if (task != null) {
                    task.run();
                }
            } catch (RuntimeException exception) {
                log.warn("页面后台依赖任务失败：{}", key, exception);
            } finally {
                gate.doneBackground();
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                if (elapsedMs > 2_000) {
                    log.info("页面后台依赖任务耗时 {} ms：{}", elapsedMs, key);
                }
            }
        }
    }

    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("页面索引步骤 {} 失败，继续后续步骤", name, exception);
        }
    }

    private boolean isTemplate(String title) {
        String value = Strings.trimToEmpty(title);
        List<String> prefixes = wikitextProperties.getTemplatePrefixes();
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (!Strings.isBlank(prefix) && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        backgroundWorker.interrupt();
    }
}
