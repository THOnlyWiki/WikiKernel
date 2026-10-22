package cc.thonly.wikikernel.plugin.reverseproxy;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;
import cc.thonly.wikikernel.plugin.reverseproxy.web.ReverseProxyHeadProvider;
import cc.thonly.wikikernel.plugin.reverseproxy.web.ReverseProxySettingsSection;
import cc.thonly.wikikernel.plugin.reverseproxy.web.ReverseProxySpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 动态路由反向代理插件。
 *
 * <ul>
 *   <li>管理员在 {@code 特殊:反向代理} 配置 {@code /proxy_<slug>/} → 上游网站；</li>
 *   <li>{@code /proxy_<slug>/**} 由插件 Controller 转发，目标 host 来自配置，不能由
 *       请求 URL 任意指定；</li>
 *   <li>默认阻断内网 / 回环目标、隔离 Cookie、过滤凭据头，并可对 HTML 加 CSP sandbox；</li>
 *   <li>可选 HTML / CSS URL 改写，适合静态站点；复杂 SPA 的兼容性限制见管理页说明。</li>
 * </ul>
 */
public class ReverseProxyPlugin implements WikiPlugin {

    public static final String ID = "reverse-proxy";

    @Autowired
    private ReverseProxyConfigService config;

    @Autowired
    private ReverseProxySupport support;

    @Autowired
    private AccessCheck accessCheck;

    @Autowired
    private CurrentUser currentUser;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "ReverseProxy", "1.0.0",
                "Admin-defined /proxy_<route>/ reverse proxy with private-network blocking,"
                        + " cookie isolation and optional HTML/CSS URL rewriting",
                List.of(), "", List.of(), ReverseProxyConfigService.FEATURE_KEY, true);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new ReverseProxyMessages());
        context.register(SpecialPage.class,
                new ReverseProxySpecialPage(config, support, accessCheck, currentUser));
        context.register(SiteSettingsSection.class, new ReverseProxySettingsSection(support));
        context.register(HtmlHeadProvider.class, new ReverseProxyHeadProvider(support));
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用反向代理：特殊:反向代理 可配置 /proxy_<路由名>/；默认阻断内网目标并隔离 Cookie"
                : "反向代理已关闭（features.reverse-proxy=false）：/proxy_* 返回 404");
    }
}
