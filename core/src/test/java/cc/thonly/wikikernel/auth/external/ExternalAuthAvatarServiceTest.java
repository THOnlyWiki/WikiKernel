package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthAvatarBridge;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 第三方注册头像调度：provider 开关 / 头像 URL / 桥的调用与容错。 */
class ExternalAuthAvatarServiceTest {

    private static final ExternalAuthUser PROFILE = new ExternalAuthUser(
            "1", "octocat", "Octocat", "", false,
            "https://avatars.githubusercontent.com/u/1?v=4");

    @Test
    void importsWhenProviderOptsInAndBridgeSucceeds() {
        PluginManager plugins = mock(PluginManager.class);
        ExternalAuthProvider provider = mock(ExternalAuthProvider.class);
        ExternalAuthAvatarBridge bridge = mock(ExternalAuthAvatarBridge.class);
        when(provider.id()).thenReturn("github");
        when(provider.importAvatarOnRegister()).thenReturn(true);
        when(plugins.extensions(ExternalAuthAvatarBridge.class)).thenReturn(List.of(bridge));
        when(bridge.importFromUrl(42L, "github", PROFILE.avatarUrl())).thenReturn(true);

        new ExternalAuthAvatarService(plugins).importAfterRegistration(provider, 42L, PROFILE);

        verify(bridge).importFromUrl(42L, "github", PROFILE.avatarUrl());
    }

    @Test
    void skipsWhenProviderDoesNotOptIn() {
        PluginManager plugins = mock(PluginManager.class);
        ExternalAuthProvider provider = mock(ExternalAuthProvider.class);
        ExternalAuthAvatarBridge bridge = mock(ExternalAuthAvatarBridge.class);
        when(provider.id()).thenReturn("github");
        when(provider.importAvatarOnRegister()).thenReturn(false);
        when(plugins.extensions(ExternalAuthAvatarBridge.class)).thenReturn(List.of(bridge));

        new ExternalAuthAvatarService(plugins).importAfterRegistration(provider, 42L, PROFILE);

        verifyNoInteractions(bridge);
    }

    @Test
    void skipsWhenProviderHasNoAvatar() {
        PluginManager plugins = mock(PluginManager.class);
        ExternalAuthProvider provider = mock(ExternalAuthProvider.class);
        ExternalAuthAvatarBridge bridge = mock(ExternalAuthAvatarBridge.class);
        when(provider.id()).thenReturn("github");
        when(provider.importAvatarOnRegister()).thenReturn(true);
        when(plugins.extensions(ExternalAuthAvatarBridge.class)).thenReturn(List.of(bridge));
        ExternalAuthUser noAvatar = new ExternalAuthUser("1", "octocat", "Octocat",
                "", false, "");

        new ExternalAuthAvatarService(plugins).importAfterRegistration(provider, 42L, noAvatar);

        verifyNoInteractions(bridge);
        verify(bridge, never()).importFromUrl(42L, "github", "");
    }
}
