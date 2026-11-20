package cc.thonly.wikikernel.plugin.externalauth.discord;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.externalauth.OAuth2ProfileProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

public final class DiscordAuthProvider extends OAuth2ProfileProvider {
    private final ObjectMapper json = new ObjectMapper();

    public DiscordAuthProvider(SiteConfig config) {
        super(config, "https://discord.com/oauth2/authorize", "https://discord.com/api/oauth2/token",
                "https://discord.com/api/v10/users/@me");
    }

    @Override public String id() { return "discord"; }
    @Override public String displayName() { return "Discord"; }
    @Override public int order() { return 40; }
    @Override public List<String> scopes() { return List.of("identify", "email"); }

    @Override
    protected String bearerToken(String responseBody) {
        JsonNode token = json.readTree(responseBody);
        return "Bearer".equalsIgnoreCase(token.path("token_type").asText(""))
                ? token.path("access_token").asText("") : "";
    }

    @Override
    protected ExternalAuthUser profile(String responseBody) {
        JsonNode user = json.readTree(responseBody);
        String subject = user.path("id").asText("");
        if (!user.path("id").isString() || !subject.matches("[0-9]{1,20}")) {
            throw new ExternalAuthException("Discord profile is missing a valid user ID");
        }

        String username = user.path("username").asText("");
        String displayName = user.path("global_name").asText("");
        String email = user.path("email").asText("").trim();
        boolean verified = !email.isBlank() && user.path("verified").isBoolean() && user.path("verified").asBoolean();
        return new ExternalAuthUser(subject, username, displayName.isBlank() ? username : displayName,
                verified ? email : "", verified, "");
    }
}
