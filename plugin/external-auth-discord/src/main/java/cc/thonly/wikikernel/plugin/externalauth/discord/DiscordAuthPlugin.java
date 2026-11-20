package cc.thonly.wikikernel.plugin.externalauth.discord;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;

public final class DiscordAuthPlugin implements WikiPlugin {
    @Autowired
    private SiteConfig siteConfig;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("external-auth-discord", "Discord", "1.0.0", "Discord OAuth login, registration and account binding");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(ExternalAuthProvider.class, new DiscordAuthProvider(siteConfig));
    }
}
