package cc.thonly.wikikernel.plugin.hitcounters.hook;

import cc.thonly.wikikernel.common.extension.PageViewEvent;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersConfig;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersPlugin;

/**
 * 页面浏览计数（对应 MediaWiki HitCounters 的 {@code PageViewUpdates} 钩子 + {@code ViewCountUpdate}）：
 * 关闭计数 / 机器人 / 豁免用户组不计数；每次浏览立即 +1。
 */
public class HitCountersViewHook implements PageViewHook {

    private final HitCountersConfig config;
    private final PagePropertyStore store;

    public HitCountersViewHook(HitCountersConfig config, PagePropertyStore store) {
        this.config = config;
        this.store = store;
    }

    @Override
    public void pageViewed(PageViewEvent event) {
        if (config.disabled() || event.bot()) {
            return;
        }
        for (String group : config.exemptGroups()) {
            if (event.groups().contains(group)) {
                return;
            }
        }
        store.addToInt(event.pageId(), HitCountersPlugin.VIEWS_PROPERTY, 1);
    }
}
