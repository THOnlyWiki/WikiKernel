package cc.thonly.wikikernel.plugin.externalauth.google;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAuthProviderTest {
    private final GoogleAuthProvider provider = new GoogleAuthProvider(new SiteConfig() {
        @Override public String get(String key, String fallback) { return "fixture"; }
        @Override public boolean getBoolean(String key, boolean fallback) { return false; }
    });

    @Test
    void requestsOnlyProfileScopesWithS256Pkce() throws Exception {
        assertFalse(provider.available());
        String callback = "https://wiki.example/auth/google/callback";
        URI authorization = URI.create(provider.authorizationUrl("fixture-state", callback, "fixture-challenge"));
        assertEquals("accounts.google.com", authorization.getHost());
        assertTrue(authorization.getRawQuery().contains("response_type=code"));
        assertTrue(authorization.getRawQuery().contains("scope=openid+profile+email"));
        assertTrue(authorization.getRawQuery().contains("code_challenge_method=S256&code_challenge=fixture-challenge"));
        assertThrows(ExternalAuthException.class, () -> provider.authorizationUrl("fixture-state", callback));
    }

    @Test
    void mapsStableIdentityAndOnlyAcceptsVerifiedEmail() {
        ExternalAuthUser user = provider.profile("""
                {"sub":"102451178243509013713","name":"示例用户","email":"user@gmail.com","email_verified":true}
                """);
        assertEquals("102451178243509013713", user.subject());
        assertEquals("示例用户", user.displayName());
        assertEquals("user@gmail.com", user.email());
        assertTrue(user.emailVerified());

        ExternalAuthUser withoutVerifiedEmail = provider.profile("""
                {"sub":"102451178243509013713","name":"示例用户","email":"user@example.com","email_verified":true}
                """);
        assertFalse(withoutVerifiedEmail.emailVerified());
        assertEquals("", withoutVerifiedEmail.email());
        assertThrows(ExternalAuthException.class, () -> provider.profile("""
                {"name":"示例用户","email":"user@gmail.com","email_verified":true}
                """));
    }
}
