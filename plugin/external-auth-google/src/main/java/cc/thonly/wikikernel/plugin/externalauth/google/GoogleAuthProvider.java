package cc.thonly.wikikernel.plugin.externalauth.google;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.externalauth.OAuth2ProfileProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

public final class GoogleAuthProvider extends OAuth2ProfileProvider {
    private final ObjectMapper json = new ObjectMapper();

    public GoogleAuthProvider(SiteConfig config) {
        super(config, "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo");
    }

    @Override public String id() { return "google"; }
    @Override public String displayName() { return "Google"; }
    @Override public int order() { return 30; }
    @Override public List<String> scopes() { return List.of("openid", "profile", "email"); }

    @Override
    protected String bearerToken(String responseBody) {
        JsonNode token = json.readTree(responseBody);
        return "Bearer".equalsIgnoreCase(token.path("token_type").asText(""))
                ? token.path("access_token").asText("") : "";
    }

    @Override
    protected ExternalAuthUser profile(String responseBody) {
        JsonNode user = json.readTree(responseBody);
        String subject = user.path("sub").asText("");
        if (!user.path("sub").isString() || subject.isBlank()) {
            throw new ExternalAuthException("Google profile is missing the subject");
        }

        String name = user.path("name").asText("");
        String email = user.path("email").asText("").trim();
        // Google is authoritative for Gmail and accounts with a Google Workspace hosted domain.
        boolean authoritativeEmail = email.toLowerCase(Locale.ROOT).endsWith("@gmail.com")
                || (user.path("hd").isString() && !user.path("hd").asText("").isBlank());
        boolean verified = !email.isBlank() && authoritativeEmail
                && user.path("email_verified").isBoolean() && user.path("email_verified").asBoolean();
        return new ExternalAuthUser(subject, "", name, verified ? email : "", verified, "");
    }
}
