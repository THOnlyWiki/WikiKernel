package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStore;
import cc.thonly.wikikernel.common.service.LoginSession;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 浏览器指纹登录插件测试用的替身集合（不启动 Spring / core）：
 * 内存版 {@link FingerprintStore} / {@link LoginSession} / {@link RateLimit} / {@link SiteConfig} /
 * {@link CurrentUser}，外加把 {@code @Autowired} 字段灌进入口类的反射助手。
 * <p>
 * 内存存储刻意实现与插件自己的 {@code FingerprintStoreJdbc} 相同的规则
 * （只能删自己的、每用户上限、同一摘要更新、别人的摘要拒绝），
 * 这样控制器 / 业务层的测试才有意义；真实的 SQL 行为由 {@code FingerprintStoreJdbcTest}（H2）覆盖。
 */
public final class FingerprintLoginFakes {

    private FingerprintLoginFakes() {
    }

    /** 把值灌进插件入口类的私有字段（模拟 Spring 的字段注入）。 */
    public static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("测试注入失败: " + field, exception);
        }
    }

    /** 站点设置替身：所有键返回默认值，只有显式给出的键有值。 */
    public static SiteConfig siteConfig(Map<String, String> values) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                String value = values.get(key);
                return value == null ? defaultValue : value;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                String value = values.get(key);
                if (value == null) {
                    return defaultValue;
                }
                return "true".equalsIgnoreCase(value) || "1".equals(value)
                        || "on".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
            }
        };
    }

    /** 功能开关（{@code features.fingerprint-login}）替身。 */
    public static SiteConfig featureConfig(boolean enabled) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(FingerprintLoginSupport.ENABLED_KEY, Boolean.toString(enabled));
        return siteConfig(values);
    }

    /** 文案替身：返回键名（等价于「漏翻时显示键名」），带参数时把 {0} 换成参数。 */
    public static MessageSource messageSource() {
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                return defaultMessage == null ? apply(code, args) : defaultMessage;
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return apply(code, args);
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    private static String apply(String code, Object[] args) {
        String value = code;
        if (args == null) {
            return value;
        }
        for (int index = 0; index < args.length; index++) {
            value = value.replace("{" + index + "}", String.valueOf(args[index]));
        }
        return value;
    }

    /** 当前用户替身（可切换登录状态）。 */
    public static CurrentUser currentUser(long userId, String name) {
        return () -> userId <= 0
                ? Viewer.ANONYMOUS
                : new Viewer(userId, name, false, java.util.Set.of(), false);
    }

    /** 全空的插件配置替身（不设 {@code https-only}）。 */
    public static PluginConfig emptyPluginConfig() {
        return new PluginConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public Map<String, String> asMap() {
                return Map.of();
            }
        };
    }

    /** 内存指纹存储：与插件自己的 {@code FingerprintStoreJdbc} 同一套规则。 */
    public static final class MemoryFingerprintStore implements FingerprintStore {

        /** 已登记的指纹（按 id 升序）。 */
        public final List<WikiFingerprint> rows = new ArrayList<>();
        /** add / remove / touch 的调用记录（断言用）。 */
        public final List<String> calls = new ArrayList<>();
        private long nextId = 1;

        @Override
        public List<WikiFingerprint> byUser(long userId) {
            return rows.stream()
                    .filter(row -> row.getUserId() == userId)
                    .sorted((left, right) -> Long.compare(right.getId(), left.getId()))
                    .toList();
        }

        @Override
        public Optional<WikiFingerprint> byHash(String hash) {
            if (hash == null || hash.isBlank()) {
                return Optional.empty();
            }
            return rows.stream().filter(row -> hash.trim().equals(row.getFingerprintHash())).findFirst();
        }

        @Override
        public int countByUser(long userId) {
            return (int) rows.stream().filter(row -> row.getUserId() == userId).count();
        }

        @Override
        public WikiFingerprint add(long userId, String label, String hash, String userAgent) {
            calls.add("add:" + userId);
            WikiFingerprint existing = byHash(hash).orElse(null);
            if (existing != null) {
                if (existing.getUserId() != userId) {
                    throw new IllegalArgumentException("fingerprint registered to another user");
                }
                existing.setLabel(label);
                existing.setUserAgent(userAgent);
                return existing;
            }
            if (countByUser(userId) >= MAX_PER_USER) {
                throw new IllegalStateException("fingerprint limit reached");
            }
            WikiFingerprint created = new WikiFingerprint(nextId++, userId, label, hash, userAgent,
                    LocalDateTime.now().minusDays(1), null);
            rows.add(created);
            return created;
        }

        @Override
        public boolean remove(long userId, long id) {
            calls.add("remove:" + userId + ":" + id);
            return rows.removeIf(row -> row.getId() == id && row.getUserId() == userId);
        }

        @Override
        public void touch(long id) {
            calls.add("touch:" + id);
            rows.stream().filter(row -> row.getId() == id).forEach(row -> row.setLastUsedAt(LocalDateTime.now()));
        }

        /** 直接塞一行（准备测试数据；绕过上限检查）。 */
        public WikiFingerprint seed(long userId, String label, String hash) {
            WikiFingerprint row = new WikiFingerprint(nextId++, userId, label, hash, "UA/1.0",
                    LocalDateTime.now().minusDays(2), null);
            rows.add(row);
            return row;
        }
    }

    /** 登录会话桥替身：记录 {@code establish} 调用，不碰真实 HttpSession。 */
    public static final class RecordingLoginSession implements LoginSession {

        /** (userId, remember) 调用记录。 */
        public final List<String> established = new ArrayList<>();
        /** establish 是否抛异常（模拟「用户已被删除」）。 */
        public boolean failEstablish;
        public int logoutCount;

        @Override
        public void establish(long userId, boolean remember) {
            if (failEstablish) {
                throw new IllegalArgumentException("user not found: " + userId);
            }
            established.add(userId + ":" + remember);
        }

        @Override
        public void logout() {
            logoutCount++;
        }
    }

    /** 限流桥替身：按「分类 + 键」记失败次数，达到 maxAttempts 后锁定。 */
    public static final class MemoryRateLimit implements RateLimit {

        private final Map<String, Integer> failures = new LinkedHashMap<>();
        private final Map<String, Long> lockedUntil = new LinkedHashMap<>();
        /** 窗口内允许的失败次数（<= 0 = 不限制）。 */
        public int maxAttempts = 2;
        /** 锁定时长（秒）。 */
        public long lockSeconds = 300;

        @Override
        public Decision check(String category, String key) {
            Long until = lockedUntil.get(mapKey(category, key));
            if (until == null || until <= System.currentTimeMillis()) {
                return Decision.allowed();
            }
            return Decision.blocked(Math.max(1, (until - System.currentTimeMillis() + 999) / 1000));
        }

        @Override
        public void hit(String category, String key) {
            if (maxAttempts <= 0) {
                return;
            }
            String mapKey = mapKey(category, key);
            int count = failures.merge(mapKey, 1, Integer::sum);
            if (count >= maxAttempts) {
                lockedUntil.put(mapKey, System.currentTimeMillis() + lockSeconds * 1000L);
                failures.put(mapKey, 0);
            }
        }

        @Override
        public void success(String category, String key) {
            String mapKey = mapKey(category, key);
            failures.remove(mapKey);
            lockedUntil.remove(mapKey);
        }

        /** 某个键当前是否被锁定（断言用）。 */
        public boolean limited(String category, String key) {
            return check(category, key).limited();
        }

        private static String mapKey(String category, String key) {
            return category + "|" + key;
        }
    }
}
