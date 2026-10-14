package cc.thonly.wikikernel.plugin.hitcounters.hook;

import cc.thonly.wikikernel.common.extension.PageRenderHook;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersConfig;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersPlugin;
import cc.thonly.wikikernel.plugin.hitcounters.HitCountersSupport;
import org.springframework.context.MessageSource;

/**
 * 页脚浏览量（对应 MediaWiki HitCounters 的 {@code SkinAddFooterLinks}：本页面已被访问 N 次）。
 * WikiKernel 插件改不了核心页脚模板，这里把计数追加到正文末尾（视觉位置不同，语义一致）；
 * 文案走插件自己的消息包（{@code i18n/messages*.properties} 的 {@code hitcounters.footer} 键）。
 */
public class HitCountersFooterHook implements PageRenderHook {

    private final HitCountersConfig config;
    private final PagePropertyStore store;
    private final WikiPageStore pages;
    private final MessageSource messageSource;

    public HitCountersFooterHook(HitCountersConfig config, PagePropertyStore store, WikiPageStore pages,
                                 MessageSource messageSource) {
        this.config = config;
        this.store = store;
        this.pages = pages;
        this.messageSource = messageSource;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String afterRender(String slug, String html) {
        if (config.disabled() || !config.footer()) {
            return html;
        }
        PageSnapshot page = pages.byTitle(slug).orElse(null);
        if (page == null) {
            return html;
        }
        long views = HitCountersSupport.parseLong(
                store.get(page.id(), HitCountersPlugin.VIEWS_PROPERTY).orElse("0"));
        if (views <= 0) {
            return html;
        }
        return html + "<div class=\"hitcount-footer\">"
                + HitCountersSupport.text(messageSource, "hitcounters.footer", HitCountersSupport.format(views))
                + "</div>";
    }
}
