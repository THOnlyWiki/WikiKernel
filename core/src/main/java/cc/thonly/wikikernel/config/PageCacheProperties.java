package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 页面正文缓存配置（LocalSettings.yaml 的 {@code wikikernel.cache.page}）：
 * 这里写的是<b>兜底默认值</b>——站点设置（{@code wiki_setting} 里的 {@code cache.page.*}）里
 * 有同名键时以站点设置为准，因此可以在「特殊:权限配置 → 站点设置」里运行时修改，立即生效。
 *
 * <pre>
 * wikikernel:
 *   cache:
 *     page:
 *       enabled: false              # 总开关，默认关闭（开箱即安全）
 *       backend: auto               # auto / redis / memory / none
 *       ttl-seconds: 300            # 条目有效期
 *       max-entries: 500            # 进程内 LRU 上限（条数）
 *       preload-on-template-change: true
 *       preload-parallelism: 8      # 不写 = CPU 核数
 *       preload-batch-size: 0       # 0 = 自动（并发上限 × 8）
 *       redis:
 *         enabled: false            # spring.data.redis.* 没配置时用它强制启用 Redis
 *         key-prefix: "wikikernel:pagecache:"   # Redis 键前缀（也是「纪元」键的前缀）
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.cache.page")
public class PageCacheProperties {

    /** Redis 键前缀的默认值：与旧版硬编码一致，升级后仍读到同一批键。 */
    public static final String DEFAULT_REDIS_KEY_PREFIX = "wikikernel:pagecache:";

    /** 是否启用页面正文缓存；{@code null} = 用内置默认（关闭）。 */
    private Boolean enabled;

    /** 后端：{@code auto}（有 Redis 用 Redis，否则进程内 LRU）/ {@code redis} / {@code memory} / {@code none}。 */
    private String backend;

    /** 条目有效期（秒）；{@code null} = 内置默认 300。 */
    private Integer ttlSeconds;

    /** 进程内 LRU 的条目上限；{@code null} = 内置默认 500。 */
    private Integer maxEntries;

    /** 模板变更后是否批量预渲染受影响页面；{@code null} = 内置默认（开启）。 */
    private Boolean preloadOnTemplateChange;

    /** 预渲染并发上限；{@code null} = CPU 核数。 */
    private Integer preloadParallelism;

    /** 单批预渲染的页面数；{@code null} 或 {@code 0} = 自动（并发上限 × 8）。 */
    private Integer preloadBatchSize;

    /** Redis 开关（{@code spring.data.redis.*} 未配置时的强制启用项）。 */
    private Redis redis = new Redis();

    /** Redis 相关配置。 */
    @Data
    public static class Redis {

        /** 是否启用 Redis（仍然要连得上才会真正使用；连不上自动退回进程内 LRU）。 */
        private Boolean enabled = false;

        /**
         * Redis 键前缀（默认 {@link #DEFAULT_REDIS_KEY_PREFIX}）：
         * 换来隔离同一台 Redis 上的多套站点（条目键与「纪元」键都用它）。
         * 空 / 空白 = 默认前缀；不以 {@code :} 结尾时会自动补一个。
         * 注意：连接与键前缀都是启动时绑定的，改完要重启才生效。
         */
        private String keyPrefix = DEFAULT_REDIS_KEY_PREFIX;
    }

    /** 规范化 Redis 键前缀：去首尾空白、补结尾 {@code :}；空 = 默认前缀。 */
    public static String normalizeRedisKeyPrefix(String raw) {
        if (raw == null) {
            return DEFAULT_REDIS_KEY_PREFIX;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_REDIS_KEY_PREFIX;
        }
        return trimmed.endsWith(":") ? trimmed : trimmed + ":";
    }
}
