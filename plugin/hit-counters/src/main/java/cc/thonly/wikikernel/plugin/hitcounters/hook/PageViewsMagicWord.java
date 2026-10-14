package cc.thonly.wikikernel.plugin.hitcounters.hook;

import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersConfig;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersPlugin;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersSupport;

/**
 * {@code {{NUMBEROFPAGEVIEWS}}}：当前页面的浏览量（对应 MediaWiki HitCounters 的同名魔术字；
 * 页面不存在 / 特殊页返回空）。
 */
public class PageViewsMagicWord implements MagicWord {

    private final HitCountersConfig config;
    private final PagePropertyStore store;
    private final WikiPageStore pages;

    public PageViewsMagicWord(HitCountersConfig config, PagePropertyStore store, WikiPageStore pages) {
        this.config = config;
        this.store = store;
        this.pages = pages;
    }

    @Override
    public String name() {
        return "NUMBEROFPAGEVIEWS";
    }

    @Override
    public String value(WikiRenderContext context) {
        if (config.disabled()) {
            return "";
        }
        PageSnapshot page = pages.byTitle(context.pageTitle()).orElse(null);
        if (page == null) {
            return "";
        }
        return HitCountersSupport.format(
                HitCountersSupport.parseLong(store.get(page.id(), HitCountersPlugin.VIEWS_PROPERTY).orElse("0")));
    }
}
