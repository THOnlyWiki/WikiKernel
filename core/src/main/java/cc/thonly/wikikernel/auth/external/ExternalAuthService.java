package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.data.WikiExternalIdentity;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.RegistrationHookService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.RequestEditContext;
import cc.thonly.wikikernel.web.RequestRegistrationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 第三方登录 / 注册 / 绑定流程。
 * <p>
 * 登录：已有绑定直接登录；未绑定且开启自动注册时创建本地账号并绑定。
 * 绑定：已登录用户从「参数设置 → 第三方授权」发起，带 {@code link=1}；回调只绑定到当前会话用户。
 */
@Service
public class ExternalAuthService {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuthService.class);
    private static final String STATE_ATTRIBUTE = "wikiExternalAuthState";
    private static final String UNLINK_TOKEN_ATTRIBUTE = "wikiExternalAuthUnlinkToken";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalAuthProviderRegistry providers;
    private final SiteSettingsService settings;
    private final ExternalIdentityService identities;
    private final UserService userService;
    private final FeatureService featureService;
    private final RegistrationHookService registrationHooks;
    private final BlockService blockService;
    private final TitleBlacklistService titleBlacklistService;
    private final MessageSource messageSource;

    public ExternalAuthService(ExternalAuthProviderRegistry providers,
                               SiteSettingsService settings,
                               ExternalIdentityService identities,
                               UserService userService,
                               FeatureService featureService,
                               RegistrationHookService registrationHooks,
                               BlockService blockService,
                               TitleBlacklistService titleBlacklistService,
                               MessageSource messageSource) {
        this.providers = providers;
        this.settings = settings;
        this.identities = identities;
        this.userService = userService;
        this.featureService = featureService;
        this.registrationHooks = registrationHooks;
        this.blockService = blockService;
        this.titleBlacklistService = titleBlacklistService;
        this.messageSource = messageSource;
    }

    /** 登录 / 注册页上的 provider 按钮。 */
    public List<Map<String, Object>> availableProviders() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExternalAuthProvider provider : providers.all()) {
            if (!provider.available()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", provider.id());
            row.put("label", provider.displayName());
            result.add(row);
        }
        return result;
    }

    /**
     * 发起 OAuth：生成 state、写入 session、返回 provider 授权地址。
     *
     * @param link true = 绑定到当前登录用户；false = 登录 / 自动注册
     */
    public String start(String providerId, boolean link, String returnTo, HttpServletRequest request) {
        ExternalAuthProvider provider = requireAvailable(providerId);
        WikiUser current = UserSession.current(request);
        if (link && current == null) {
            throw new ExternalAuthException(message("auth.external.linkLoginRequired"));
        }
        String safeReturnTo = ExternalAuthSettings.safeReturnTo(returnTo,
                link ? WikiUrls.pagePath("特殊:参数设置") : "/");
        String state = randomToken(32);
        ExternalAuthState authState = new ExternalAuthState(
                provider.id(), state, link, current == null ? null : current.getId(),
                safeReturnTo, System.currentTimeMillis());
        request.getSession(true).setAttribute(STATE_ATTRIBUTE, authState);
        try {
            return provider.authorizationUrl(state, redirectUri(provider, request));
        } catch (Exception exception) {
            log.warn("第三方登录授权地址生成失败 provider={} error={}",
                    provider.id(), exception.getClass().getSimpleName());
            throw new ExternalAuthException(message("auth.external.providerUnavailable"));
        }
    }

    /**
     * 处理 provider 回调。
     *
     * @return 登录用户（绑定流程为 null）+ 最终跳转地址
     */
    public Result callback(String providerId, String code, String error, String state,
                           HttpServletRequest request) {
        ExternalAuthProvider provider = requireAvailable(providerId);
        ExternalAuthState authState = consumeState(request, provider.id(), state);
        if (error != null && !error.isBlank()) {
            throw new ExternalAuthException(message("auth.external.cancelled"));
        }
        if (code == null || code.isBlank()) {
            throw new ExternalAuthException(message("auth.external.missingCode"));
        }

        ExternalAuthUser profile;
        try {
            profile = provider.exchange(code, redirectUri(provider, request));
        } catch (ExternalAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("第三方登录换取用户失败 provider={} error={}",
                    provider.id(), exception.getClass().getSimpleName());
            throw new ExternalAuthException(message("auth.external.exchangeFailed"), exception);
        }
        if (profile == null || profile.subject().isBlank()) {
            throw new ExternalAuthException(message("auth.external.identityMissing"));
        }

        if (authState.link()) {
            return linkToCurrentUser(provider, profile, authState, request);
        }

        Optional<WikiExternalIdentity> existing = identities.find(provider.id(), profile.subject());
        if (existing.isPresent()) {
            WikiExternalIdentity identity = existing.get();
            WikiUser user = userService.findById(identity.getUserId()).orElse(null);
            if (user == null) {
                throw new ExternalAuthException(message("auth.external.userMissing"));
            }
            identities.updateAfterLogin(identity, profile);
            return new Result(user, authState.returnTo());
        }

        WikiUser current = UserSession.current(request);
        if (current != null) {
            throw new ExternalAuthException(message("auth.external.alreadyLoggedIn"));
        }
        if (!settings.getBoolean(ExternalAuthSettings.AUTO_REGISTER_KEY, true)) {
            throw new ExternalAuthException(message("auth.external.autoRegisterDisabled"));
        }
        if (!featureService.registerEnabled()) {
            throw new ExternalAuthException(message("auth.external.registerDisabled"));
        }

        WikiUser created = createUser(provider, profile, request);
        Optional<WikiExternalIdentity> linked = identities.link(created.getId(), provider, profile);
        if (linked.isEmpty()) {
            throw new ExternalAuthException(message("auth.external.linkConflict"));
        }
        return new Result(created, authState.returnTo());
    }

    private Result linkToCurrentUser(ExternalAuthProvider provider, ExternalAuthUser profile,
                                     ExternalAuthState state, HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null || state.userId() == null || !state.userId().equals(current.getId())) {
            throw new ExternalAuthException(message("auth.external.linkLoginRequired"));
        }
        Optional<WikiExternalIdentity> linked = identities.link(current.getId(), provider, profile);
        if (linked.isEmpty()) {
            throw new ExternalAuthException(message("auth.external.linkConflict"));
        }
        String target = appendQuery(state.returnTo(),
                "external=linked", "provider=" + provider.id());
        if (state.returnTo().equals(WikiUrls.pagePath("特殊:参数设置"))) {
            target += "#external";
        }
        return new Result(null, target);
    }

    private WikiUser createUser(ExternalAuthProvider provider, ExternalAuthUser profile,
                                HttpServletRequest request) {
        String email = profile.emailVerified() ? Strings.trimToEmpty(profile.email()) : "";
        if (!email.isBlank() && userService.findByEmail(email).isPresent()) {
            throw new ExternalAuthException(message("auth.external.emailExists"));
        }
        if (blockService.createBlocked()) {
            throw new ExternalAuthException(message("auth.external.registerBlocked",
                    message("block.registerForbidden")));
        }
        String username = uniqueUsername(provider, profile);
        RegistrationHookService.Decision decision = registrationHooks.beforeRegister(
                RequestRegistrationContext.web(request, username, email,
                        RequestEditContext.formParameters(request)));
        if (decision.blocked()) {
            String detail = Strings.trimToEmpty(decision.message());
            throw new ExternalAuthException(detail.isEmpty()
                    ? message("auth.external.registerBlocked", "")
                    : detail);
        }
        if (titleBlacklistService.check(username,
                TitleBlacklistService.Operation.NEW_ACCOUNT, null).isPresent()) {
            throw new ExternalAuthException(message("auth.external.usernameRejected"));
        }
        WikiUser user = userService.createUser(username, randomToken(32), email, null);
        if (!email.isBlank() && profile.emailVerified()) {
            userService.markEmailConfirmed(user.getId());
            user.setEmailConfirmed(true);
        }
        return user;
    }

    private String uniqueUsername(ExternalAuthProvider provider, ExternalAuthUser profile) {
        String base = sanitizeUsername(profile.username());
        if (base.isEmpty()) {
            base = sanitizeUsername(profile.displayName());
        }
        if (base.isEmpty()) {
            String suffix = profile.subject().replaceAll("[^A-Za-z0-9]", "");
            if (suffix.length() > 10) {
                suffix = suffix.substring(0, 10);
            }
            base = provider.id() + (suffix.isEmpty() ? "" : "_" + suffix);
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        if (!UserService.isValidUsername(base)) {
            base = provider.id() + "_user";
        }
        String candidate = base;
        for (int attempt = 2; attempt < 1000 && userService.isExisted(candidate); attempt++) {
            candidate = base + "_" + attempt;
        }
        if (!userService.isExisted(candidate)) {
            return candidate;
        }
        return provider.id() + "_" + randomToken(6).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeUsername(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (UserService.FORBIDDEN_USERNAME_CHARS.indexOf(ch) >= 0
                    || Character.isISOControl(ch)) {
                continue;
            }
            out.append(ch);
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    /** 参数设置「第三方授权」页面的 provider 列表；含当前未启用但已经绑定的 provider，方便解绑。 */
    public List<Map<String, Object>> accountsFor(WikiUser user, String unlinkToken) {
        Map<String, WikiExternalIdentity> linked = new LinkedHashMap<>();
        for (WikiExternalIdentity identity : identities.forUser(user.getId())) {
            linked.put(identity.getProvider(), identity);
        }
        String preferences = WikiUrls.pagePath("特殊:参数设置");
        String encodedPreferences = urlEncode(preferences);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExternalAuthProvider provider : providers.all()) {
            WikiExternalIdentity identity = linked.get(provider.id());
            if (!provider.available() && identity == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", provider.id());
            row.put("label", provider.displayName());
            row.put("enabled", provider.enabled());
            row.put("configured", provider.configured());
            row.put("linked", identity != null);
            row.put("identityName", identity == null ? "" : first(identity.getUsername(),
                    identity.getEmail(), identity.getSubject()));
            row.put("linkedAt", identity == null ? null : identity.getLinkedAt());
            row.put("linkUrl", identity == null && provider.available()
                    ? "/auth/" + provider.id() + "/start?link=1&returnto=" + encodedPreferences
                    : "");
            row.put("unlinkUrl", identity != null
                    ? "/auth/" + provider.id() + "/unlink?token=" + urlEncode(unlinkToken)
                    + "&returnto=" + encodedPreferences
                    : "");
            rows.add(row);
        }
        return rows;
    }

    public String unlinkToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(UNLINK_TOKEN_ATTRIBUTE);
        if (existing instanceof String token && !token.isBlank()) {
            return token;
        }
        String token = randomToken(24);
        session.setAttribute(UNLINK_TOKEN_ATTRIBUTE, token);
        return token;
    }

    public boolean verifyUnlinkToken(HttpServletRequest request, String token) {
        if (request == null || token == null || token.isBlank()) {
            return false;
        }
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(UNLINK_TOKEN_ATTRIBUTE);
        if (!(expected instanceof String value)) {
            return false;
        }
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    public boolean unlink(long userId, String providerId) {
        return identities.unlink(userId, providerId);
    }

    private ExternalAuthProvider requireAvailable(String providerId) {
        ExternalAuthProvider provider = providers.find(providerId)
                .orElseThrow(() -> new ExternalAuthException(message("auth.external.disabled")));
        if (!provider.available()) {
            throw new ExternalAuthException(message("auth.external.disabled"));
        }
        return provider;
    }

    private ExternalAuthState consumeState(HttpServletRequest request, String providerId, String state) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(STATE_ATTRIBUTE);
        if (!(value instanceof ExternalAuthState authState)) {
            throw new ExternalAuthException(message("auth.external.expired"));
        }
        session.removeAttribute(STATE_ATTRIBUTE);
        if (state == null || state.isBlank()
                || !MessageDigest.isEqual(authState.state().getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            throw new ExternalAuthException(message("auth.external.stateMismatch"));
        }
        if (!authState.provider().equals(providerId)) {
            throw new ExternalAuthException(message("auth.external.stateMismatch"));
        }
        if (System.currentTimeMillis() - authState.createdAt() > STATE_TTL.toMillis()) {
            throw new ExternalAuthException(message("auth.external.expired"));
        }
        return authState;
    }

    private String redirectUri(ExternalAuthProvider provider, HttpServletRequest request) {
        return ExternalAuthSettings.callbackUrl(provider, settings, request);
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String appendQuery(String url, String... pairs) {
        StringBuilder out = new StringBuilder(url);
        out.append(url.contains("?") ? '&' : '?');
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                out.append('&');
            }
            out.append(pairs[index]);
        }
        return out.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 一次回调的结果；user 为 null 表示只完成了绑定。 */
    public record Result(WikiUser user, String redirectTo) {
    }

    private record ExternalAuthState(String provider, String state, boolean link, Long userId,
                                     String returnTo, long createdAt) implements Serializable {
    }
}

