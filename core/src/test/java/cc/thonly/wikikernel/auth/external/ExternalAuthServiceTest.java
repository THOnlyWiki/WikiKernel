package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.data.WikiExternalIdentity;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.RegistrationHookService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalAuthServiceTest {

    @Test
    void autoRegistersAndReturnsUserOnFirstLogin() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String state = fixture.start(request, false);
        ExternalAuthService.Result result = fixture.service.callback(
                "test", "code", null, state, request);
        assertEquals(42L, result.user().getId());
        assertEquals("/wiki/首页", result.redirectTo());
    }

    @Test
    void linksProviderToCurrentUserWithoutLoggingInAgain() throws Exception {
        Fixture fixture = new Fixture();
        WikiUser current = user(7L, "Current");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(UserSession.ATTRIBUTE, current);
        String state = fixture.start(request, true);
        ExternalAuthService.Result result = fixture.service.callback(
                "test", "code", null, state, request);
        assertNull(result.user());
        assertTrue(result.redirectTo().contains("external=linked"));
    }

    private static WikiUser user(long id, String name) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    private static final class Fixture {
        private final AtomicReference<String> state = new AtomicReference<>();
        private final ExternalAuthService service;

        private Fixture() throws Exception {
            ExternalAuthProvider provider = mock(ExternalAuthProvider.class);
            when(provider.id()).thenReturn("test");
            when(provider.displayName()).thenReturn("Test");
            when(provider.available()).thenReturn(true);
            when(provider.authorizationUrl(anyString(), anyString())).thenAnswer(invocation -> {
                state.set(invocation.getArgument(0));
                return "https://provider.example/authorize";
            });
            when(provider.exchange(anyString(), anyString())).thenReturn(
                    new ExternalAuthUser("subject-1", "Alice", "Alice",
                            "alice@example.com", true, ""));

            SiteSettingsService settings = mock(SiteSettingsService.class);
            when(settings.getBoolean(ExternalAuthSettings.AUTO_REGISTER_KEY, true)).thenReturn(true);
            when(settings.get(ExternalAuthSettings.BASE_URL_KEY, "")).thenReturn("");

            WikiExternalIdentity identity = new WikiExternalIdentity();
            identity.setUserId(42L);
            identity.setProvider("test");
            identity.setSubject("subject-1");
            ExternalIdentityService identities = mock(ExternalIdentityService.class);
            when(identities.find(anyString(), anyString())).thenReturn(Optional.empty());
            when(identities.link(anyLong(), any(), any())).thenReturn(Optional.of(identity));

            WikiUser created = user(42L, "Alice");
            UserService users = mock(UserService.class);
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(users.isExisted(anyString())).thenReturn(false);
            when(users.createUser(anyString(), anyString(), anyString(), isNull())).thenReturn(created);

            FeatureService features = mock(FeatureService.class);
            when(features.registerEnabled()).thenReturn(true);
            RegistrationHookService hooks = mock(RegistrationHookService.class);
            when(hooks.beforeRegister(any())).thenReturn(
                    new RegistrationHookService.Decision(RegistrationGuard.Outcome.Result.ALLOW, ""));
            BlockService blocks = mock(BlockService.class);
            when(blocks.createBlocked()).thenReturn(false);
            TitleBlacklistService blacklist = mock(TitleBlacklistService.class);
            when(blacklist.check(anyString(), any(), any())).thenReturn(Optional.empty());

            this.service = new ExternalAuthService(
                    new ExternalAuthProviderRegistry(List.of(provider)),
                    settings, identities, users, features, hooks, blocks, blacklist,
                    new StaticMessageSource());
        }

        private String start(MockHttpServletRequest request, boolean link) throws Exception {
            service.start("test", link, "/wiki/首页", request);
            return state.get();
        }
    }
}



