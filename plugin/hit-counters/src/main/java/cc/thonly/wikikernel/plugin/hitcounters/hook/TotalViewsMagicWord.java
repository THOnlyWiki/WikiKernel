package cc.thonly.wikikernel.plugin.hitcounters.hook;

import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersConfig;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersPlugin;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersSupport;

/**
 * {@code {{NUMBEROFVIEWS}}}：全站总浏览量（对应 MediaWiki HitCounters 的同名魔术字 / 统计表）。
 */
public class TotalViewsMagicWord implements MagicWord {

    private final HitCountersConfig config;
    private final PagePropertyStore store;

    public TotalViewsMagicWord(HitCountersConfig config, PagePropertyStore store) {
        this.config = config;
        this.store = store;
    }

    @Override
    public String name() {
        return "NUMBEROFVIEWS";
    }

    @Override
    public String value(WikiRenderContext context) {
        if (config.disabled()) {
            return "";
        }
        return HitCountersSupport.format(store.sumInt(HitCountersPlugin.VIEWS_PROPERTY));
    }
}
