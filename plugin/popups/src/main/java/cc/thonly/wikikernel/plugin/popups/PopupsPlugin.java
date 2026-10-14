package cc.thonly.wikikernel.plugin.popups;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Popups（页面预览）扩展移植（对应 MediaWiki 的 {@code Popups} / Page Previews，原为 core 内置功能）：
 * <ul>
 *     <li>前端脚本 {@code /plugins/popups/popups.js}：桌面端悬停正文区页面链接 0.5 秒显示摘要浮层；</li>
 *     <li>浮层样式 {@code /plugins/popups/popups.css}（原 core {@code base.css} 的 {@code .mw-popup} 区块）；</li>
 *     <li>数据来自 core 的通用 API {@code GET /api/page-preview?title=…}（保留在 core：含读权限校验，等价于
 *     MediaWiki 由 API 提供摘要、Popups 只做界面）。</li>
 * </ul>
 * 兼容性：沿用 core 的功能开关 {@code features.popups}（通过 {@link SiteConfig} 读取）；
 * 关闭时不注入脚本 / 样式（与迁移前一致）；{@code WIKI_CONFIG.popups} 开关仍由 core 注入。
 * 本地化文案在插件自己的消息包里（{@link PopupsMessages}，见 {@code i18n/messages*.properties}），
 * 浮层里的文字经 {@code window.WIKI_MESSAGES} 传给前端脚本。
 */
public class PopupsPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("popups", "Popups (Page Previews)", "0.1.0",
                "Shows page previews with a summary and thumbnail when hovering page links "
                        + "(ported from MediaWiki Popups)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new PopupsMessages());
        context.register(HtmlHeadProvider.class, new PopupsHeadProvider(siteConfig, messageSource));
        context.logger().log(System.Logger.Level.INFO,
                siteConfig.getBoolean("features.popups", true)
                        ? "已启用页面预览（Popups）"
                        : "页面预览已关闭（features.popups=false），不注入脚本");
    }
}
