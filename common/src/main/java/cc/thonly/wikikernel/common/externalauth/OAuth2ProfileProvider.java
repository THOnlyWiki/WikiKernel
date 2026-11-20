package cc.thonly.wikikernel.common.externalauth;

import cc.thonly.wikikernel.common.service.SiteConfig;

import java.net.http.HttpClient;
import java.util.Map;

import static cc.thonly.wikikernel.common.externalauth.ExternalAuthHttp.encode;

/** OAuth authorization code with S256 PKCE and a bearer-authenticated profile endpoint. */
public abstract class OAuth2ProfileProvider implements ExternalAuthProvider {
    private final SiteConfig config;
    private final String authorizeEndpoint;
    private final String tokenEndpoint;
    private final String profileEndpoint;
    private final ExternalAuthHttp http = new ExternalAuthHttp(HttpClient.Redirect.NEVER);

    protected OAuth2ProfileProvider(SiteConfig config, String authorizeEndpoint, String tokenEndpoint,
                                    String profileEndpoint) {
        this.config = config;
        this.authorizeEndpoint = authorizeEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.profileEndpoint = profileEndpoint;
    }

    @Override public final String enabledSettingKey() { return "auth." + id() + ".enabled"; }
    @Override public final String clientIdSettingKey() { return "auth." + id() + ".client-id"; }
    @Override public final String clientSecretSettingKey() { return "auth." + id() + ".client-secret"; }
    @Override public final String clientIdLabel() { return "Client ID"; }
    @Override public final String clientSecretLabel() { return "Client Secret"; }
    @Override public final boolean enabled() { return config.getBoolean(enabledSettingKey(), false); }
    @Override public final boolean configured() {
        return !setting(clientIdSettingKey()).isBlank() && !setting(clientSecretSettingKey()).isBlank();
    }

    @Override public final boolean supportsPkce() { return true; }

    @Override public final String authorizationUrl(String state, String redirectUri) {
        throw missingPkce();
    }

    @Override public final String authorizationUrl(String state, String redirectUri, String codeChallenge) {
        if (codeChallenge == null || codeChallenge.isBlank()) throw missingPkce();
        return authorizeEndpoint + "?response_type=code"
                + "&client_id=" + encode(setting(clientIdSettingKey()))
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(String.join(" ", scopes()))
                + "&state=" + encode(state)
                + "&code_challenge_method=S256&code_challenge=" + encode(codeChallenge);
    }

    @Override public final ExternalAuthUser exchange(String code, String redirectUri) {
        throw missingPkce();
    }

    @Override
    public final ExternalAuthUser exchange(String code, String redirectUri, String codeVerifier) throws Exception {
        if (codeVerifier == null || codeVerifier.isBlank()) throw missingPkce();
        ExternalAuthHttp.Response token = http.postForm(tokenEndpoint, Map.of(
                "grant_type", "authorization_code", "client_id", setting(clientIdSettingKey()),
                "client_secret", setting(clientSecretSettingKey()), "code", code,
                "redirect_uri", redirectUri, "code_verifier", codeVerifier), Map.of("Accept", "application/json"));
        if (!token.ok()) throw responseError("token", token.status());
        String accessToken = bearerToken(token.body());
        if (accessToken.isBlank()) throw new ExternalAuthException(displayName() + " returned no bearer access token");
        ExternalAuthHttp.Response response = http.get(profileEndpoint, Map.of(
                "Authorization", "Bearer " + accessToken, "Accept", "application/json", "User-Agent", "WikiKernel"));
        if (!response.ok()) throw responseError("profile", response.status());
        return profile(response.body());
    }

    protected abstract String bearerToken(String responseBody);

    protected abstract ExternalAuthUser profile(String responseBody);

    private String setting(String key) {
        String value = config.get(key, "");
        return value == null ? "" : value.trim();
    }

    private ExternalAuthException missingPkce() {
        return new ExternalAuthException(displayName() + " requires an S256 PKCE authorization request");
    }

    private ExternalAuthException responseError(String endpoint, int status) {
        return new ExternalAuthException(displayName() + " " + endpoint + " endpoint returned HTTP " + status);
    }
}
