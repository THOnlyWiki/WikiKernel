package cc.thonly.wikikernel.plugin.externalauth.github;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthHttp;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GitHub OAuth App provider（插件实现）。 */
public final class GitHubAuthProvider implements ExternalAuthProvider {

    public static final String ID = "github";

    private static final Logger log = LoggerFactory.getLogger(GitHubAuthProvider.class);

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final SiteConfig config;
    private final ExternalAuthHttp http = new ExternalAuthHttp();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubAuthProvider(SiteConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String enabledSettingKey() {
        return "auth.github.enabled";
    }

    @Override
    public String clientIdSettingKey() {
        return "auth.github.client-id";
    }

    @Override
    public String clientSecretSettingKey() {
        return "auth.github.client-secret";
    }

    @Override
    public String clientIdLabel() {
        return "Client ID";
    }

    @Override
    public String clientSecretLabel() {
        return "Client Secret";
    }

    @Override
    public List<String> scopes() {
        return List.of("read:user", "user:email");
    }

    @Override
    public boolean enabled() {
        return config.getBoolean(enabledSettingKey(), false);
    }

    @Override
    public boolean configured() {
        return !clientId().isBlank() && !clientSecret().isBlank();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return AUTHORIZE_URL
                + "?client_id=" + ExternalAuthHttp.encode(clientId())
                + "&redirect_uri=" + ExternalAuthHttp.encode(redirectUri)
                + "&scope=" + ExternalAuthHttp.encode(String.join(" ", scopes()))
                + "&state=" + ExternalAuthHttp.encode(state)
                + "&allow_signup=true";
    }

    @Override
    public ExternalAuthUser exchange(String code, String redirectUri) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId());
        form.put("client_secret", clientSecret());
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        ExternalAuthHttp.Response tokenResponse = http.postForm(TOKEN_URL, form,
                Map.of("Accept", "application/json", "User-Agent", "WikiKernel"));
        if (!tokenResponse.ok()) {
            log.warn("GitHub token endpoint returned HTTP {} (callback URI: {})",
                    tokenResponse.status(), redirectUri);
            throw new ExternalAuthException("GitHub token endpoint returned " + tokenResponse.status());
        }
        JsonNode token = objectMapper.readTree(tokenResponse.body());
        String accessToken = token.path("access_token").asText("");
        if (accessToken.isBlank()) {
            String error = token.path("error").asText("").trim();
            String description = token.path("error_description").asText("").trim();
            log.warn("GitHub token exchange failed: error={} description={} callback URI={}",
                    error, description, redirectUri);
            String detail = error.isBlank() ? "empty response" : error;
            if (!description.isBlank()) {
                detail += " (" + description + ")";
            }
            throw new ExternalAuthException("GitHub token exchange failed: " + detail);
        }

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + accessToken,
                "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28",
                "User-Agent", "WikiKernel");
        ExternalAuthHttp.Response userResponse = http.get("https://api.github.com/user", headers);
        if (!userResponse.ok()) {
            throw new ExternalAuthException("GitHub user endpoint returned " + userResponse.status());
        }
        JsonNode user = objectMapper.readTree(userResponse.body());
        String subject = user.path("id").asText("");
        String login = user.path("login").asText("");
        String name = user.path("name").asText("");
        String avatar = user.path("avatar_url").asText("");
        if (subject.isBlank() || login.isBlank()) {
            throw new ExternalAuthException("GitHub user profile incomplete");
        }

        String email = "";
        boolean verified = false;
        ExternalAuthHttp.Response emailsResponse = http.get("https://api.github.com/user/emails", headers);
        if (emailsResponse.ok()) {
            JsonNode emails = objectMapper.readTree(emailsResponse.body());
            if (emails.isArray()) {
                for (JsonNode item : emails) {
                    if (!item.path("verified").asBoolean(false)) {
                        continue;
                    }
                    String candidate = item.path("email").asText("");
                    if (candidate.isBlank()) {
                        continue;
                    }
                    if (email.isBlank() || item.path("primary").asBoolean(false)) {
                        email = candidate;
                        verified = true;
                    }
                    if (item.path("primary").asBoolean(false)) {
                        break;
                    }
                }
            }
        }
        return new ExternalAuthUser(subject, login, name.isBlank() ? login : name,
                email, verified, avatar);
    }

    private String clientId() {
        String value = config.get(clientIdSettingKey(), "");
        return value == null ? "" : value.trim();
    }

    private String clientSecret() {
        String value = config.get(clientSecretSettingKey(), "");
        return value == null ? "" : value.trim();
    }
}
