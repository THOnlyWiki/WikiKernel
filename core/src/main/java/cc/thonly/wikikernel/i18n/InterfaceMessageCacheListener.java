package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.CodePages;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class InterfaceMessageCacheListener {
    private final ObjectProvider<PageCacheService> cache;
    public InterfaceMessageCacheListener(ObjectProvider<PageCacheService> cache) { this.cache = cache; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void changed(WikiPageChangedEvent event) {
        if ((WikiNamespaces.isWikiKernel(event.title()) || WikiNamespaces.isMediaWiki(event.title()))
                && !CodePages.isInterfacePage(event.title())) {
            var service = cache.getIfAvailable();
            if (service != null && service.enabled()) service.invalidateAll();
        }
    }
}
