package cc.thonly.wikikernel.plugin.hitcounters;

import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageRenderHook;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.hitcounters.hook.HitCountersFooterHook;
import cc.thonly.wikikernel.plugin.hitcounters.hook.HitCountersViewHook;
import cc.thonly.wikikernel.plugin.hitcounters.hook.PageViewsMagicWord;
import cc.thonly.wikikernel.plugin.hitcounters.hook.TotalViewsMagicWord;
import cc.thonly.wikikernel.plugin.hitcounters.web.PopularPagesSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * HitCounters 扩展移植（对应 MediaWiki 的 {@code HitCounters} 扩展 0.4）：
 * <ul>
 *     <li>词条阅读时累计浏览量（跳过机器人 / 豁免组 / 特殊页 / 旧版本 / diff，对应 {@code PageViewUpdates}）；</li>
 *     <li>魔术字 {@code {{NUMBEROFVIEWS}}}（全站总浏览）与 {@code {{NUMBEROFPAGEVIEWS}}}（本页浏览）；</li>
 *     <li>特殊页「特殊:最多浏览」（对应 {@code Special:PopularPages}）；</li>
 *     <li>页脚显示「本页面已被访问 N 次」（对应 {@code SkinAddFooterLinks} 的 viewcount）。</li>
 * </ul>
 * 计数存在 core 的页面属性表（{@code wiki_page_property}，属性名 {@code hitcounter-views}），
 * 并登记为「持久属性」——页面重新解析保存时不会被渲染产出覆盖。
 * <p>
 * 与 MediaWiki 版的差异：{@code $wgHitcounterUpdateFreq} 的批量合并未实现（每次浏览立即 +1）；
 * 页脚计数显示在正文末尾而不是页脚栏。
 */
public class HitCountersPlugin implements WikiPlugin {

    /** 计数保存的页面属性名。 */
    public static final String VIEWS_PROPERTY = "hitcounter-views";

    @Autowired
    private PagePropertyStore pagePropertyStore;

    @Autowired
    private WikiPageStore wikiPageStore;

    /** 本地化文案（core 的 MessageSource 已合并插件消息）。 */
    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("hit-counters", "HitCounters", "0.4.0",
                "Page view counting with the {{NUMBEROFVIEWS}} magic word, Special:PopularPages and a footer counter");
    }

    @Override
    public void onStart(PluginContext context) {
        HitCountersConfig config = HitCountersConfig.from(context.config());
        // 浏览计数是持久数据：页面重新解析保存时不覆盖
        pagePropertyStore.registerDurable(VIEWS_PROPERTY);

        context.register(MessageBundle.class, new HitCountersMessages());
        context.register(MagicWord.class, new TotalViewsMagicWord(config, pagePropertyStore));
        context.register(MagicWord.class, new PageViewsMagicWord(config, pagePropertyStore, wikiPageStore));
        context.register(PageViewHook.class, new HitCountersViewHook(config, pagePropertyStore));
        context.register(PageRenderHook.class,
                new HitCountersFooterHook(config, pagePropertyStore, wikiPageStore, messageSource));
        context.register(SpecialPage.class,
                new PopularPagesSpecialPage(config, pagePropertyStore, wikiPageStore, messageSource));
        context.register(SetupStep.class, new HitCountersSetup(config, messageSource));
        context.logger().log(System.Logger.Level.INFO, config.disabled()
                ? "浏览计数已关闭（disabled=true）"
                : "已启用浏览计数（{{NUMBEROFVIEWS}} / 特殊:最多浏览）");
    }
}
