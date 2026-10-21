package cc.thonly.wikikernel.plugin.externalauth.github;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;

/** GitHub OAuth 第三方登录插件。 */
public class GitHubAuthPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("external-auth-github", "GitHub", "1.0.0",
                "GitHub OAuth login and registration");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(ExternalAuthProvider.class, new GitHubAuthProvider(siteConfig));
        context.logger().log(System.Logger.Level.INFO,
                "已注册第三方登录 provider：GitHub（在特殊:权限配置 → 第三方登录中配置）");
    }
}
