package cc.thonly.wikikernel.plugin.fingerprintlogin.service;

import cc.thonly.wikikernel.common.service.LoginSession;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;
import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 浏览器指纹登录的核心业务（Spring Bean：{@code @RestController} 与该特殊页 / 参数设置分区共用）。
 * <p>
 * 职责：
 * <ul>
 *     <li><b>登录</b>：指纹摘要 → 用户（{@link FingerprintStore#byHash}），命中则经 {@link LoginSession}
 *     建立与密码登录一致的会话，并 {@link FingerprintStore#touch} 更新 {@code lastUsedAt}；
 *     未命中一律给**中性**错误（不泄露「哪个用户有这个指纹」，也不区分「没登记」与「登记了但被禁用」）；</li>
 *     <li><b>限流</b>：按「客户端 IP + 指纹摘要」分类限流（{@link RateLimit}，规则
 *     {@code wikikernel.rate-limit.fingerprint-login}），防止拿一批候选摘要刷登录；</li>
 *     <li><b>管理</b>：列出 / 添加 / 删除**自己**的指纹（每用户最多
 *     {@link FingerprintStore#MAX_PER_USER} 个）。管理员也看不到别人的指纹列表（隐私）。</li>
 * </ul>
 * 指纹摘要只在浏览器端用 {@code crypto.subtle.digest('SHA-256')} 计算，服务端不重算、不做二次哈希
 * （摘要本身就是不可逆摘要，直接存 {@code wiki_fingerprint.fingerprint_hash}）。
 */
@Service
public class FingerprintLoginService {

    /** 限流分类：按「客户端 IP + 指纹摘要」（与 core 的 {@code RateLimiterService.FINGERPRINT_LOGIN} 同名）。 */
    public static final String RATE_LIMIT_CATEGORY = "fingerprint-login";

    /** 限流分类：只按客户端 IP（与 core 的 {@code RateLimiterService.FINGERPRINT_LOGIN_IP} 同名）。 */
    public static final String RATE_LIMIT_CATEGORY_IP = "fingerprint-login-ip";

    /** 指纹摘要的合法格式：十六进制（SHA-256 为 64 位，允许其它算法到 128 位）。 */
    private static final String HASH_PATTERN = "[0-9a-fA-F]{16," + FingerprintLoginSupport.HASH_MAX_LENGTH + "}";

    /** 管理页一屏最多列出的指纹条数（上限本身就是 10，这里只是防御）。 */
    private static final int LIST_LIMIT = 50;

    private final FingerprintStore fingerprints;
    private final LoginSession loginSession;
    private final RateLimit rateLimit;
    private final FingerprintLoginSupport support;

    public FingerprintLoginService(FingerprintStore fingerprints,
                                   LoginSession loginSession,
                                   RateLimit rateLimit,
                                   FingerprintLoginSupport support) {
        this.fingerprints = fingerprints;
        this.loginSession = loginSession;
        this.rateLimit = rateLimit;
        this.support = support;
    }

    /** core 的数据桥是否都就绪（真实实例上恒为 true；缺失时接口拒绝服务而不是抛 NPE）。 */
    public boolean bridgesReady() {
        return fingerprints != null && loginSession != null && rateLimit != null && support != null;
    }

    // ==================== 登录 ====================

    /**
     * 用浏览器指纹登录。
     *
     * @param hash     浏览器端算出的指纹摘要（十六进制）
     * @param remember 是否延长会话有效期（等价于密码登录表单的「记住我的登录状态」）
     * @param clientIp 客户端 IP（限流键的一部分）
     * @param secure   当前请求是否安全上下文（与 {@link #httpsAllowed(boolean)} 一起判断）
     */
    public LoginOutcome login(String hash, boolean remember, String clientIp, boolean secure) {
        if (!bridgesReady()) {
            return LoginOutcome.failure(support.message("fingerprint.error.saveFailed"));
        }
        if (!support.enabled()) {
            return LoginOutcome.failure(support.message("fingerprint.error.disabled"));
        }
        if (!httpsAllowed(secure)) {
            return LoginOutcome.failure(support.message("fingerprint.httpsOnly"));
        }
        if (hash == null || hash.isBlank()) {
            return LoginOutcome.failure(support.message("fingerprint.error.hashRequired"));
        }
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(HASH_PATTERN)) {
            return LoginOutcome.failure(support.message("fingerprint.error.hashInvalid"));
        }
        String key = rateLimitKey(clientIp, normalized);
        String ipKey = rateLimitIpKey(clientIp);
        // 两级限流：按「IP + 指纹」（同一设备重复尝试）与按「IP」（换指纹绕过）都要检查
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, key);
        RateLimit.Decision ipDecision = rateLimit.check(RATE_LIMIT_CATEGORY_IP, ipKey);
        if (decision.limited() || ipDecision.limited()) {
            long retryAfter = Math.max(decision.retryAfterSeconds(), ipDecision.retryAfterSeconds());
            return LoginOutcome.failure(support.message("fingerprint.error.rateLimited", retryAfter));
        }
        WikiFingerprint matched = fingerprints.byHash(normalized).orElse(null);
        if (matched == null || matched.getUserId() == null || matched.getUserId() <= 0L) {
            rateLimit.hit(RATE_LIMIT_CATEGORY, key);
            rateLimit.hit(RATE_LIMIT_CATEGORY_IP, ipKey);
            // 中性提示：不区分「没登记」「用户已不存在」，也不透露归属
            return LoginOutcome.failure(support.message("fingerprint.error.badCredentials"));
        }
        try {
            loginSession.establish(matched.getUserId(), remember);
        } catch (RuntimeException exception) {
            // 用户已被删除 / 没有请求上下文：同样给中性提示（也要计失败，防止拿已删用户刷）
            rateLimit.hit(RATE_LIMIT_CATEGORY, key);
            rateLimit.hit(RATE_LIMIT_CATEGORY_IP, ipKey);
            return LoginOutcome.failure(support.message("fingerprint.error.badCredentials"));
        }
        fingerprints.touch(matched.getId());
        // 成功后清除失败计数（两级都清）
        rateLimit.success(RATE_LIMIT_CATEGORY, key);
        rateLimit.success(RATE_LIMIT_CATEGORY_IP, ipKey);
        return LoginOutcome.success(support.message("fingerprint.login.success"));
    }

    /** 是否允许在当前上下文使用指纹登录：未开「仅 HTTPS 允许」时总是允许，开了则要求安全上下文。 */
    public boolean httpsAllowed(boolean secure) {
        return !support.httpsOnly() || secure;
    }

    /** 限流键：客户端 IP + 指纹摘要（摘要是公开值，不进日志，只作计数键）。 */
    public static String rateLimitKey(String clientIp, String hash) {
        return normalizedIp(clientIp) + "|" + (hash == null ? "" : hash);
    }

    /** 限流键：只按客户端 IP（防换指纹绕过）。 */
    public static String rateLimitIpKey(String clientIp) {
        return normalizedIp(clientIp);
    }

    private static String normalizedIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    }

    // ==================== 管理 ====================

    /** 某个用户自己的指纹列表（最新登记的在前面）；未启用的功能返回空列表。 */
    public List<FingerprintView> list(long userId) {
        if (!bridgesReady() || !support.enabled()) {
            return List.of();
        }
        return fingerprints.byUser(userId).stream()
                .limit(LIST_LIMIT)
                .map(this::view)
                .toList();
    }

    /** 供特殊页 / 参数设置分区渲染的模型行（与接口返回同一批字段）。 */
    public List<Map<String, Object>> listRows(long userId) {
        return list(userId).stream().map(FingerprintView::asModel).toList();
    }

    /**
     * 登记当前浏览器的指纹（同一摘要已属于该用户时更新名称 / UA 并返回 updated=true）。
     * 未命中 {@link FingerprintStore} 的规则时抛异常，由调用方转成错误码。
     */
    public AddOutcome add(long userId, String label, String hash, String userAgent) {
        if (!bridgesReady()) {
            throw new AddException(AddError.STORE_UNAVAILABLE);
        }
        String trimmedLabel = label == null ? "" : label.trim();
        if (trimmedLabel.isEmpty()) {
            throw new AddException(AddError.LABEL_REQUIRED);
        }
        if (trimmedLabel.length() > FingerprintLoginSupport.LABEL_MAX_LENGTH) {
            throw new AddException(AddError.LABEL_TOO_LONG);
        }
        if (hash == null || hash.isBlank()) {
            throw new AddException(AddError.HASH_REQUIRED);
        }
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(HASH_PATTERN)) {
            throw new AddException(AddError.HASH_INVALID);
        }
        boolean existed = fingerprints.byUser(userId).stream()
                .anyMatch(row -> normalized.equals(row.getFingerprintHash()));
        WikiFingerprint stored;
        try {
            stored = fingerprints.add(userId, trimmedLabel, normalized,
                    FingerprintLoginSupport.userAgentSummary(userAgent));
        } catch (IllegalStateException exception) {
            throw new AddException(AddError.LIMIT_REACHED);
        } catch (IllegalArgumentException exception) {
            throw new AddException(AddError.REGISTERED_TO_ANOTHER_USER);
        }
        return new AddOutcome(stored.getId() == null ? 0L : stored.getId(), existed);
    }

    /** 删除自己的某个指纹（别人的 / 不存在的返回 false）。 */
    public boolean remove(long userId, long id) {
        if (!bridgesReady()) {
            return false;
        }
        return id > 0 && fingerprints.remove(userId, id);
    }

    private FingerprintView view(WikiFingerprint row) {
        return FingerprintView.of(row, support.formatTime(row.getCreatedAt()),
                support.formatTime(row.getLastUsedAt()));
    }

    // ==================== 结果类型 ====================

    /** 登录结果：成功 / 失败 + 给用户看的提示（都来自消息键）。 */
    public record LoginOutcome(boolean success, String message) {

        public static LoginOutcome success(String message) {
            return new LoginOutcome(true, message);
        }

        public static LoginOutcome failure(String message) {
            return new LoginOutcome(false, message);
        }
    }

    /** 登记成功：指纹 id + 是否只更新了已有指纹。 */
    public record AddOutcome(long id, boolean updated) {
    }

    /** 登记失败的原因（调用方按它选消息键与错误码）。 */
    public enum AddError {
        LABEL_REQUIRED,
        LABEL_TOO_LONG,
        HASH_REQUIRED,
        HASH_INVALID,
        LIMIT_REACHED,
        REGISTERED_TO_ANOTHER_USER,
        /** core 的数据桥缺失（只会在插件被独立运行 / 桥未装配时出现）。 */
        STORE_UNAVAILABLE
    }

    /** 登记失败的异常载体（不写中文文案，消息键在调用方 / 控制器里选）。 */
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
