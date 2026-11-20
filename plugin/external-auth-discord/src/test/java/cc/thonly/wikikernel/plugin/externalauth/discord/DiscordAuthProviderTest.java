package cc.thonly.wikikernel.plugin.externalauth.discord;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAuthProviderTest {
    private final DiscordAuthProvider provider = new DiscordAuthProvider(new SiteConfig() {
        @Override public String get(String key, String fallback) { return "fixture"; }
        @Override public boolean getBoolean(String key, boolean fallback) { return false; }
    });

    @Test
    void requestsOnlyProfileScopesWithS256Pkce() throws Exception {
        assertFalse(provider.available());
        String callback = "https://wiki.example/auth/discord/callback";
        URI authorization = URI.create(provider.authorizationUrl("fixture-state", callback, "fixture-challenge"));
        assertEquals("discord.com", authorization.getHost());
        assertTrue(authorization.getRawQuery().contains("response_type=code"));
        assertTrue(authorization.getRawQuery().contains("scope=identify+email"));
        assertTrue(authorization.getRawQuery().contains("code_challenge_method=S256&code_challenge=fixture-challenge"));
        assertThrows(ExternalAuthException.class, () -> provider.authorizationUrl("fixture-state", callback));
    }

    @Test
    void mapsStableIdentityAndOnlyAcceptsVerifiedEmail() {
        ExternalAuthUser user = provider.profile("""
                {"id":"1234567890123456789","username":"wiki_user","global_name":"示例用户","email":"user@example.com","verified":true}
                """);
        assertEquals("1234567890123456789", user.subject());
        assertEquals("示例用户", user.displayName());
        assertEquals("user@example.com", user.email());
        assertTrue(user.emailVerified());

        ExternalAuthUser withoutVerifiedEmail = provider.profile("""
                {"id":"1234567890123456789","username":"wiki_user","email":"user@example.com","verified":false}
                """);
        assertFalse(withoutVerifiedEmail.emailVerified());
        assertEquals("", withoutVerifiedEmail.email());
        assertThrows(ExternalAuthException.class, () -> provider.profile("""
                {"username":"wiki_user","email":"user@example.com","verified":true}
                """));
    }
}
