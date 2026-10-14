package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.RateLimitProperties;
import cc.thonly.wikikernel.service.RateLimiterService;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版速率限制：按「分类 + 键」用滑动窗口统计失败次数，
 * 窗口内失败达到 {@code max-attempts} 后锁定 {@code lock-seconds} 秒；成功一次立即清除。
 * <p>
 * 条目惰性清理（超过 24 小时没再出现且未锁定时移除），避免内存膨胀。
 */
@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    /** 累计条目超过该值后顺手清理过期条目。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    /** 超过该时长没再出现的条目可清理（毫秒）。 */
    private static final long STALE_MILLIS = 24L * 60 * 60 * 1000;

    private final RateLimitProperties properties;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiterServiceImpl(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public Decision check(String category, String key) {
        RateLimitProperties.Rule rule = ruleOf(category);
        if (!properties.isEnabled() || rule.getMaxAttempts() <= 0) {
            return Decision.allowed();
        }
        Counter counter = counters.get(mapKey(category, key));
        if (counter == null) {
            return Decision.allowed();
        }
        long now = System.currentTimeMillis();
        synchronized (counter) {
            counter.lastTouched = now;
            if (counter.lockedUntil > now) {
                return Decision.blocked(secondsUntil(counter.lockedUntil, now));
            }
            prune(counter, rule, now);
        }
        return Decision.allowed();
    }

    @Override
    public void failure(String category, String key) {
        RateLimitProperties.Rule rule = ruleOf(category);
        if (!properties.isEnabled() || rule.getMaxAttempts() <= 0) {
            return;
        }
        Counter counter = counters.computeIfAbsent(mapKey(category, key), ignored -> new Counter());
        synchronized (counter) {
            long now = System.currentTimeMillis();
            counter.lastTouched = now;
            prune(counter, rule, now);
            if (counter.lockedUntil > now) {
                return; // 已在锁定中，不重复计数
            }
            counter.failures.addLast(now);
            if (counter.failures.size() >= rule.getMaxAttempts()) {
                counter.lockedUntil = now + Math.max(0, rule.getLockSeconds()) * 1000L;
                counter.failures.clear();
            }
        }
        cleanupIfNeeded();
    }

    @Override
    public void success(String category, String key) {
        counters.remove(mapKey(category, key));
    }

    // ==================== 内部实现 ====================

    /** 每个键的失败时间点（滑动窗口）与锁定截止时间。 */
    static final class Counter {
        final ArrayDeque<Long> failures = new ArrayDeque<>();
        long lockedUntil;
        long lastTouched = System.currentTimeMillis();
    }

    /** 分类 → 规则（未知分类回落到 login 规则）。 */
    private RateLimitProperties.Rule ruleOf(String category) {
        if (SETUP.equals(category)) {
            return properties.getSetup();
        }
        if (PASSWORD_RESET.equals(category)) {
            return properties.getPasswordReset();
        }
        if (AI_AGENT.equals(category)) {
            return properties.getAiAgent();
        }
        if (FINGERPRINT_LOGIN.equals(category)) {
            return properties.getFingerprintLogin();
        }
        if (FINGERPRINT_LOGIN_IP.equals(category)) {
            return properties.getFingerprintLoginIp();
        }
        return properties.getLogin();
    }

    /** 丢掉窗口外的失败记录。 */
    private static void prune(Counter counter, RateLimitProperties.Rule rule, long now) {
        long windowStart = now - Math.max(0, rule.getWindowSeconds()) * 1000L;
        while (!counter.failures.isEmpty() && counter.failures.peekFirst() < windowStart) {
            counter.failures.pollFirst();
        }
    }

    private static long secondsUntil(long until, long now) {
        return Math.max(1, (until - now + 999) / 1000);
    }

    private static String mapKey(String category, String key) {
        return Strings.trimToEmpty(category) + "|" + Strings.trimToEmpty(key).toLowerCase(Locale.ROOT);
    }

    /** 条目过多时清理长时间未出现且未锁定的键。 */
    private void cleanupIfNeeded() {
        if (counters.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(entry -> {
            Counter counter = entry.getValue();
            synchronized (counter) {
                return counter.lockedUntil < now && now - counter.lastTouched > STALE_MILLIS;
            }
        });
    }
}
