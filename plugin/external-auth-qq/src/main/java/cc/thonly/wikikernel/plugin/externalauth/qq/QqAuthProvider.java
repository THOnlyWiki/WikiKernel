package cc.thonly.wikikernel.plugin.externalauth.qq;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthHttp;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** QQ 互联 OAuth provider（插件实现；QQ 通常不返回邮箱）。 */
public final class QqAuthProvider implements ExternalAuthProvider {

    public static final String ID = "qq";

    private static final String AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize";
    private static final String TOKEN_URL = "https://graph.qq.com/oauth2.0/token";
    private static final String OPENID_URL = "https://graph.qq.com/oauth2.0/me";
    private static final String USERINFO_URL = "https://graph.qq.com/user/get_user_info";

    private final SiteConfig config;
    private final ExternalAuthHttp http = new ExternalAuthHttp();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QqAuthProvider(SiteConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "QQ";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String enabledSettingKey() {
        return "auth.qq.enabled";
    }

    @Override
    public String clientIdSettingKey() {
        return "auth.qq.app-id";
    }

    @Override
    public String clientSecretSettingKey() {
        return "auth.qq.app-key";
    }

    @Override
    public String clientIdLabel() {
        return "App ID";
    }

    @Override
    public String clientSecretLabel() {
        return "App Key";
    }

    @Override
    public List<String> scopes() {
        return List.of("get_user_info");
    }

    @Override
    public boolean enabled() {
        return config.getBoolean(enabledSettingKey(), false);
    }

    @Override
    public boolean configured() {
        return !appId().isBlank() && !appKey().isBlank();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + ExternalAuthHttp.encode(appId())
                + "&redirect_uri=" + ExternalAuthHttp.encode(redirectUri)
                + "&scope=" + ExternalAuthHttp.encode(String.join(" ", scopes()))
                + "&state=" + ExternalAuthHttp.encode(state);
    }

    @Override
    public ExternalAuthUser exchange(String code, String redirectUri) throws Exception {
        String tokenUrl = TOKEN_URL
                + "?grant_type=authorization_code"
                + "&client_id=" + ExternalAuthHttp.encode(appId())
                + "&client_secret=" + ExternalAuthHttp.encode(appKey())
                + "&code=" + ExternalAuthHttp.encode(code)
                + "&redirect_uri=" + ExternalAuthHttp.encode(redirectUri)
                + "&fmt=json";
        ExternalAuthHttp.Response tokenResponse = http.get(tokenUrl, Map.of("User-Agent", "WikiKernel"));
        if (!tokenResponse.ok()) {
            throw new ExternalAuthException("QQ token endpoint returned " + tokenResponse.status());
        }
        JsonNode token = null;
        try {
            token = parseJson(tokenResponse.body());
        } catch (Exception ignored) {
            // 少数 QQ 应用即使传 fmt=json 仍返回 form-urlencoded。
        }
        String accessToken = token == null
                ? queryValue(tokenResponse.body(), "access_token")
                : token.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new ExternalAuthException("QQ token exchange failed");
        }

        String openIdUrl = OPENID_URL
                + "?access_token=" + ExternalAuthHttp.encode(accessToken)
                + "&fmt=json";
        ExternalAuthHttp.Response openIdResponse = http.get(openIdUrl, Map.of("User-Agent", "WikiKernel"));
        if (!openIdResponse.ok()) {
            throw new ExternalAuthException("QQ openid endpoint returned " + openIdResponse.status());
        }
        JsonNode openId = parseJson(openIdResponse.body());
        String subject = openId.path("openid").asText("");
        if (subject.isBlank()) {
            throw new ExternalAuthException("QQ openid missing");
        }

        String userInfoUrl = USERINFO_URL
                + "?access_token=" + ExternalAuthHttp.encode(accessToken)
                + "&oauth_consumer_key=" + ExternalAuthHttp.encode(appId())
                + "&openid=" + ExternalAuthHttp.encode(subject)
                + "&fmt=json";
        ExternalAuthHttp.Response userResponse = http.get(userInfoUrl, Map.of("User-Agent", "WikiKernel"));
        if (!userResponse.ok()) {
            throw new ExternalAuthException("QQ user endpoint returned " + userResponse.status());
        }
        JsonNode user = parseJson(userResponse.body());
        if (user.path("ret").asInt(0) != 0) {
            throw new ExternalAuthException("QQ user profile rejected");
        }
        String nickname = user.path("nickname").asText("");
        String avatar = user.path("figureurl_qq_2").asText("");
        if (avatar.isBlank()) {
            avatar = user.path("figureurl_qq_1").asText("");
        }
        String name = nickname.isBlank() ? "QQ " + subject.substring(0, Math.min(8, subject.length())) : nickname;
        return new ExternalAuthUser(subject, name, name, "", false, avatar);
    }

    /** QQ 部分接口即使 fmt=json 也会返回 callback({...});，统一剥掉包装。 */
    private JsonNode parseJson(String body) throws Exception {
        String text = body == null ? "" : body.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ExternalAuthException("QQ response is not JSON");
        }
        return objectMapper.readTree(text.substring(start, end + 1));
    }

    /** 从 form-urlencoded / query 串里取一个值（QQ 的 fmt=json 不保证生效时的兜底）。 */
    private static String queryValue(String body, String key) {
        String text = body == null ? "" : body.trim();
        for (String pair : text.split("&")) {
            int equal = pair.indexOf('=');
            if (equal <= 0) {
                continue;
            }
            if (key.equals(pair.substring(0, equal))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(equal + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    return pair.substring(equal + 1);
                }
            }
        }
        return "";
    }

    private String appId() {
        String value = config.get(clientIdSettingKey(), "");
        return value == null ? "" : value.trim();
    }

    private String appKey() {
        String value = config.get(clientSecretSettingKey(), "");
        return value == null ? "" : value.trim();
    }
}
