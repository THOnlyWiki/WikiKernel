package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 页面变更事件 → 异步索引队列。
 * <p>
 * 只在事务提交后入队，后台线程读到的才是已提交的最新版本；保存请求本身不执行
 * 分类 / 模板 / 页面属性 / 缓存 / TypeScript 等任何重建或失效工作，入队后立即返回。
 * 当前页面正文缓存的失效也由后台队列完成：缓存条目带版本号，读取时版本不一致
 * 会按未命中处理，因此不会把旧正文返回给用户。
 */
@Component
public class PageIndexEventListener {

    private final PageIndexService pageIndexService;

    public PageIndexEventListener(PageIndexService pageIndexService) {
        this.pageIndexService = pageIndexService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPageChanged(WikiPageChangedEvent event) {
        if (event == null) {
            return;
        }
        // 只做并发容器写入 + 队列 offer；不碰数据库、Redis 或渲染。
        pageIndexService.enqueue(event);
    }
}
