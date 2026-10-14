package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.service.PageBody;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis 后端：条目序列化成 JSON 存 {@code StringRedisTemplate}，TTL 交给 Redis 自己过期。
 * <p>
 * <b>键设计</b>：{@code <前缀>p<纪元>:<页面标题>}，前缀默认
 * {@link PageCacheProperties#DEFAULT_REDIS_KEY_PREFIX}（可用
 * {@code wikikernel.cache.page.redis.key-prefix} 改，便于多站点共用一台 Redis）。
 * 「纪元」（{@code <前缀>epoch}）是一个 Redis 计数器：
 * {@link #clear()} 只把它 +1，旧键立刻不可达并在 TTL 内自然过期 ——
 * 这样 {@code invalidateAll} 是 O(1)，不需要在生产 Redis 上跑 {@code KEYS} / {@code SCAN}。
 * 纪元值在进程内缓存 {@link #EPOCH_REFRESH_MILLIS} 毫秒，所以别的实例清空缓存后，
 * 本实例最迟这么久就会跟着切到新纪元。
 * <p>
 * 所有异常都向外抛，由 {@link PageCacheServiceImpl} 捕获后降级到进程内 LRU。
 */
final class RedisPageCacheBackend implements PageCacheBackend {

    /** 探测用的键后缀（不存在也没关系：连不上会抛异常，连得上返回 null）。 */
    private static final String PROBE_SUFFIX = "probe";

    /** 版本纪元键后缀。 */
    private static final String EPOCH_SUFFIX = "epoch";

    /** 纪元在进程内的缓存时长（毫秒）：避免每次读写都多一次往返。 */
    private static final long EPOCH_REFRESH_MILLIS = 2000L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** 键前缀来源（配置里现读，空 = 默认前缀）。 */
    private final Supplier<String> keyPrefixSupplier;
    private volatile long epoch;
    private volatile long epochReadAt;

    RedisPageCacheBackend(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          Supplier<String> keyPrefixSupplier) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefixSupplier = keyPrefixSupplier;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Optional<PageBody> get(String key) {
        String value = this.redisTemplate.opsForValue().get(redisKey(key));
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(this.objectMapper.readValue(value, PageBody.class));
    }

    @Override
    public void put(String key, PageBody body, Duration ttl) {
        this.redisTemplate.opsForValue().set(redisKey(key), this.objectMapper.writeValueAsString(body), ttl);
    }

    @Override
    public void remove(String key) {
        this.redisTemplate.delete(redisKey(key));
    }

    @Override
    public void clear() {
        Long value = this.redisTemplate.opsForValue().increment(keyPrefix() + EPOCH_SUFFIX);
        this.epoch = value == null ? this.epoch + 1 : value;
        this.epochReadAt = System.currentTimeMillis();
    }

    @Override
    public boolean available() {
        // 读一个不一定存在的键：服务器不可达会被 Lettuce 抛成运行时异常，能返回就说明连得上
        this.redisTemplate.opsForValue().get(keyPrefix() + PROBE_SUFFIX);
        return true;
    }

    /** 带纪元的 Redis 键（标题原样拼在键尾，Redis 键是二进制安全的）。 */
    private String redisKey(String key) {
        return keyPrefix() + "p" + currentEpoch() + ":" + key;
    }

    /** 当前键前缀（空 = 默认；不以 {@code :} 结尾时自动补上）。 */
    private String keyPrefix() {
        return PageCacheProperties.normalizeRedisKeyPrefix(
                this.keyPrefixSupplier == null ? null : this.keyPrefixSupplier.get());
    }

    /** 当前纪元（进程内缓存，最多 {@link #EPOCH_REFRESH_MILLIS} 毫秒重新读一次）。 */
    private long currentEpoch() {
        long now = System.currentTimeMillis();
        if (this.epochReadAt > 0 && now - this.epochReadAt < EPOCH_REFRESH_MILLIS) {
            return this.epoch;
        }
        String value = this.redisTemplate.opsForValue().get(keyPrefix() + EPOCH_SUFFIX);
        long parsed = 0L;
        if (value != null && !value.isBlank()) {
            try {
                parsed = Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                parsed = 0L;
            }
        }
        this.epoch = parsed;
        this.epochReadAt = now;
        return parsed;
    }
}
