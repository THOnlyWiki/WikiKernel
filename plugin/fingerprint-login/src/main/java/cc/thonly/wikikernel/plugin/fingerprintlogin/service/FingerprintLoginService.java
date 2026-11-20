package cc.thonly.wikikernel.plugin.fingerprintlogin.service;

import cc.thonly.wikikernel.common.service.LoginSession;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;
import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 浏览器凭据只通过 Cookie 传输，数据库只保存带版本前缀的摘要。 */
@Service
public class FingerprintLoginService {
    public static final String RATE_LIMIT_CATEGORY = "fingerprint-login";
    public static final String RATE_LIMIT_CATEGORY_IP = "fingerprint-login-ip";
    public static final Duration CREDENTIAL_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FingerprintStore fingerprints;
    private final LoginSession loginSession;
    private final RateLimit rateLimit;
    private final FingerprintLoginSupport support;

    public FingerprintLoginService(FingerprintStore fingerprints, LoginSession loginSession,
                                   RateLimit rateLimit, FingerprintLoginSupport support) {
        this.fingerprints = fingerprints;
        this.loginSession = loginSession;
        this.rateLimit = rateLimit;
        this.support = support;
    }

    public boolean bridgesReady() {
        return fingerprints != null && loginSession != null && rateLimit != null && support != null;
    }

    public LoginOutcome login(String credential, boolean remember, String clientIp, boolean secure) {
        if (!bridgesReady()) {
            return failure("fingerprint.error.saveFailed");
        }
        if (!support.enabled()) {
            return failure("fingerprint.error.disabled");
        }
        if (!secure) {
            return failure("fingerprint.httpsOnly");
        }
        String currentDigest = credentialDigest(credential);
        String key = rateLimitKey(clientIp, currentDigest);
        String ipKey = rateLimitIpKey(clientIp);
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, key);
        RateLimit.Decision ipDecision = rateLimit.check(RATE_LIMIT_CATEGORY_IP, ipKey);
        if (decision.limited() || ipDecision.limited()) {
            return failure("fingerprint.error.rateLimited",
                    Math.max(decision.retryAfterSeconds(), ipDecision.retryAfterSeconds()));
        }
        WikiFingerprint matched = currentDigest == null ? null : fingerprints.byHash(currentDigest).orElse(null);
        if (matched == null || matched.getUserId() == null || matched.getUserId() <= 0 || expired(matched)) {
            return rejected(key, ipKey);
        }
        String next = newCredential();
        if (!fingerprints.rotate(matched.getId(), currentDigest, credentialDigest(next), null, null)) {
            return rejected(key, ipKey);
        }
        try {
            loginSession.establish(matched.getUserId(), remember);
        } catch (RuntimeException exception) {
            return rejected(key, ipKey);
        }
        rateLimit.success(RATE_LIMIT_CATEGORY, key);
        rateLimit.success(RATE_LIMIT_CATEGORY_IP, ipKey);
        return new LoginOutcome(true, support.message("fingerprint.login.success"), next);
    }

    private LoginOutcome rejected(String key, String ipKey) {
        rateLimit.hit(RATE_LIMIT_CATEGORY, key);
        rateLimit.hit(RATE_LIMIT_CATEGORY_IP, ipKey);
        return failure("fingerprint.error.badCredentials");
    }

    private LoginOutcome failure(String key, Object... args) {
        return new LoginOutcome(false, support.message(key, args), null);
    }

    public static String rateLimitKey(String clientIp, String digest) {
        return rateLimitIpKey(clientIp) + "|" + (digest == null ? "" : digest);
    }

    public static String rateLimitIpKey(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    }

    public List<FingerprintView> list(long userId) {
        if (!bridgesReady() || !support.enabled()) return List.of();
        return fingerprints.byUser(userId).stream()
                .limit(FingerprintStore.MAX_PER_USER)
                .map(row -> FingerprintView.of(row, support.formatTime(row.getCreatedAt()),
                        support.formatTime(row.getLastUsedAt())))
                .toList();
    }

    public List<Map<String, Object>> listRows(long userId) {
        return list(userId).stream().map(FingerprintView::asModel).toList();
    }

    public AddOutcome add(long userId, String label, String previousCredential, String userAgent) {
        if (!bridgesReady() || !support.enabled()) throw new AddException(AddError.STORE_UNAVAILABLE);

        String name = label == null ? "" : label.trim();
        if (name.isEmpty()) throw new AddException(AddError.LABEL_REQUIRED);
        if (name.length() > FingerprintLoginSupport.LABEL_MAX_LENGTH) {
            throw new AddException(AddError.LABEL_TOO_LONG);
        }

        String credential = newCredential();
        String digest = credentialDigest(credential);
        String previousDigest = credentialDigest(previousCredential);
        WikiFingerprint previous = previousDigest == null ? null : fingerprints.byHash(previousDigest).orElse(null);
        String agent = FingerprintLoginSupport.userAgentSummary(userAgent);
        if (previous != null && previous.getUserId() == userId) {
            if (!fingerprints.rotate(previous.getId(), previousDigest, digest, name, agent)) {
                throw new AddException(AddError.STORE_UNAVAILABLE);
            }
            return new AddOutcome(previous.getId(), true, credential);
        }
        try {
            WikiFingerprint stored = fingerprints.add(userId, name, digest, agent);
            return new AddOutcome(stored.getId(), false, credential);
        } catch (IllegalStateException exception) {
            throw new AddException(AddError.LIMIT_REACHED);
        }
    }

    public boolean remove(long userId, long id) {
        return bridgesReady() && id > 0 && fingerprints.remove(userId, id);
    }

    private static boolean expired(WikiFingerprint row) {
        LocalDateTime activity = row.getLastUsedAt() == null ? row.getCreatedAt() : row.getLastUsedAt();
        return activity == null || !activity.plus(CREDENTIAL_LIFETIME).isAfter(LocalDateTime.now());
    }

    private static String newCredential() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String credentialDigest(String credential) {
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.US_ASCII));
            // Existing entries contain unversioned browser fingerprints and cannot match.
            return "v2:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record LoginOutcome(boolean success, String message, String credential) { }

    public record AddOutcome(long id, boolean updated, String credential) { }

    public enum AddError { LABEL_REQUIRED, LABEL_TOO_LONG, LIMIT_REACHED, STORE_UNAVAILABLE }

    public static class AddException extends RuntimeException {
        private final AddError error;

        public AddException(AddError error) {
            super(error.name());
            this.error = error;
        }

        public AddError error() {
            return error;
        }
    }
}
