package cc.thonly.wikikernel.plugin.externalauth.qq;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;

/** QQ 互联 OAuth 第三方登录插件。 */
public class QqAuthPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("external-auth-qq", "QQ", "1.0.0",
                "QQ OAuth login and registration");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(ExternalAuthProvider.class, new QqAuthProvider(siteConfig));
        context.logger().log(System.Logger.Level.INFO,
                "已注册第三方登录 provider：QQ（在特殊:权限配置 → 第三方登录中配置）");
    }
}
