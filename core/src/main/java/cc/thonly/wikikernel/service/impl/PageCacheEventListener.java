package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 页面变更 → 页面正文缓存失效（与分类 / 模板索引同一套事件机制）。
 * <ul>
 *     <li>页面创建 / 编辑 / 删除 / 移动：失效该页缓存（条目本身带版本号，双保险）；</li>
 *     <li>模板（{@code 模板:} 命名空间，前缀见 {@code wikikernel.wikitext.template-prefixes}）变更：
 *     额外失效<b>所有嵌入它的页面</b>，并按配置用虚拟线程批量预渲染预热；</li>
 *     <li>移动页面时旧标题的条目不再被使用（标题下已无页面，且版本号对不上），由 TTL / 条数上限淘汰。</li>
 * </ul>
 * 用 {@link TransactionPhase#AFTER_COMMIT}：保存事务提交后才失效与预热，
 * 后台预渲染线程不会和写入事务抢数据库锁；没有事务时（例如脚本直接发事件）照常执行。
 */
@Component
public class PageCacheEventListener {

    private final PageCacheService pageCacheService;
    private final WikitextProperties wikitextProperties;

    public PageCacheEventListener(PageCacheService pageCacheService,
                                  WikitextProperties wikitextProperties) {
        this.pageCacheService = pageCacheService;
        this.wikitextProperties = wikitextProperties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPageChanged(WikiPageChangedEvent event) {
        if (!this.pageCacheService.enabled()) {
            // 缓存关闭：没有条目需要失效，也不必预热
            return;
        }
        String title = event.title();
        if (Strings.isBlank(title)) {
            return;
        }
        this.pageCacheService.invalidate(title);
        if (isTemplate(title)) {
            this.pageCacheService.invalidateTranscluding(title);
        }
    }

    /** 是否是模板页（前缀来自 {@code wikikernel.wikitext.template-prefixes}，大小写不敏感）。 */
    private boolean isTemplate(String title) {
        List<String> prefixes = this.wikitextProperties.getTemplatePrefixes();
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (!Strings.isBlank(prefix) && title.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }
}
