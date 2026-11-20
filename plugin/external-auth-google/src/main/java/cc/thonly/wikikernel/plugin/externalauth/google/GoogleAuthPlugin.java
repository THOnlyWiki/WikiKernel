package cc.thonly.wikikernel.plugin.externalauth.google;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;

public final class GoogleAuthPlugin implements WikiPlugin {
    @Autowired
    private SiteConfig siteConfig;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("external-auth-google", "Google", "1.0.0", "Google OAuth login, registration and account binding");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(ExternalAuthProvider.class, new GoogleAuthProvider(siteConfig));
    }
}
