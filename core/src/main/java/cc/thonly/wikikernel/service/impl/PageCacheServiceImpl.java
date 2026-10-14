package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.service.PageLinkStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.service.PageBody;
import cc.thonly.wikikernel.service.PageBodyRenderer;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiTitles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 页面正文缓存实现。
 * <p>
 * <b>接入方式</b>：页面查看走 {@link #body}（读缓存 → 命中直接返回 → 未命中渲染后写缓存），
 * 缓存里存的是 {@link PageBody}（HTML + 渲染元信息），因此命中时可以彻底跳过渲染。
 * <p>
 * <b>版本安全</b>：条目自带 {@code revisionId}，读取时与页面当前 {@code latestRevisionId} 比对，
 * 不一致就按未命中处理并顺手删掉 —— 页面一改版本号就变，<b>不会返回旧内容</b>。
 * 即使失效事件丢失（例如多实例部署时另一个实例改了页面），也只是多渲染一次。
 * <p>
 * <b>后端</b>：{@code cache.page.backend} = {@code auto}（默认）时，
 * 配置了 Redis（{@code spring.data.redis.*} 或 {@code wikikernel.cache.page.redis.enabled=true}）
 * 且探测连得上就用 Redis，否则用进程内 LRU（有界）；{@code none} = 不缓存。
 * Redis 键前缀用 {@code wikikernel.cache.page.redis.key-prefix}（启动时绑定，改完要重启）。
 * 后端故障（Redis 挂了 / 超时）自动降级到进程内 LRU，并停止访问 Redis
 * {@link #REDIS_RETRY_MILLIS} 毫秒后再试，业务路径永远不受影响。
 * <p>
 * <b>模板变更</b>：{@link #invalidateTranscluding} 失效所有嵌入该模板的页面，
 * 并按 {@code cache.page.preload-on-template-change} 用虚拟线程批量重新渲染预热
 * （并发上限 {@code cache.page.preload-parallelism}，批量大小 {@code cache.page.preload-batch-size}；
 * 单个页面失败只记日志）。
 * <p>
 * <b>失效范围</b>：页面创建 / 编辑 / 删除 / 移动都会失效该页（模板页面还会连带失效嵌入它的页面）。
 * 分类变更<b>不需要</b>失效正文：分类页的成员列表本来就不缓存（每次实时扫描），
 * 而页面自身加 / 减分类只影响该页自己的正文，正文已随这次保存失效；
 * 页面属性（{@code wiki_page_property}）随保存重算，所在页面同时失效，因此也是最新的。
 * 已知边界：别处新建页面导致的「红链变蓝链」不主动失效链入页面，最迟由 TTL 兜住。
 * <p>
 * 配置全部现读现用（站点设置优先、YAML 兜底），改完立即生效、无需重启。
 */
@Service
@Order(6) // 站点设置导入（3）与模板索引回填（5）之后再报告后端
public class PageCacheServiceImpl implements PageCacheService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageCacheServiceImpl.class);

    /** 后端名：进程内 LRU。 */
    static final String BACKEND_MEMORY = "memory";

    /** 后端名：Redis。 */
    static final String BACKEND_REDIS = "redis";

    /** 后端名：自动（有 Redis 用 Redis，否则内存）。 */
    static final String BACKEND_AUTO = "auto";

    /** 后端名：不缓存。 */
    static final String BACKEND_NONE = "none";

    /** 默认值：总开关（默认关闭，开箱即安全）。 */
    private static final boolean DEFAULT_ENABLED = false;

    /** 默认值：后端。 */
    private static final String DEFAULT_BACKEND = BACKEND_AUTO;

    /** 默认值：条目有效期（秒）。 */
    private static final int DEFAULT_TTL_SECONDS = 300;

    /** 默认值：进程内 LRU 条目上限。 */
    private static final int DEFAULT_MAX_ENTRIES = 500;

    /** 默认值：模板变更后是否预渲染。 */
    private static final boolean DEFAULT_PRELOAD_ON_TEMPLATE_CHANGE = true;

    /** 自动批量大小的倍率：并发上限 × 该值。 */
    private static final int AUTO_BATCH_MULTIPLIER = 8;

    /** 自动批量大小的下限。 */
    private static final int AUTO_BATCH_MIN = 16;

    /** 单次模板变更最多处理多少嵌入页面（防止一个巨型模板把站点拖垮）。 */
    private static final int MAX_TRANSCLUDING_PAGES = 5000;

    /** 并发上限的上限（配置写大了也会被夹住）。 */
    private static final int MAX_PARALLELISM = 256;

    /** 有效期的上限（秒）：最长一周。 */
    private static final int MAX_TTL_SECONDS = 7 * 24 * 3600;

    /** Redis 探测超时（毫秒）：不可达时不能让启动 / 请求线程等太久；取值要容得下首次建连（Netty / DNS）。 */
    private static final long REDIS_PROBE_TIMEOUT_MILLIS = 3000L;

    /** Redis 判定不可用后的重试间隔（毫秒）。 */
    private static final long REDIS_RETRY_MILLIS = 30_000L;

    /** 判定「配置了 Redis」的 Spring 配置键。 */
    private static final List<String> REDIS_PROPERTY_KEYS = List.of(
            "spring.data.redis.host", "spring.data.redis.port", "spring.data.redis.url",
            "spring.data.redis.username", "spring.data.redis.password", "spring.data.redis.database",
            "spring.data.redis.timeout");

    private final SiteSettingsService siteSettingsService;
    private final PageCacheProperties properties;
    private final PageBodyRenderer pageBodyRenderer;
    private final WikiPageService wikiPageService;
    private final PageLinkStore pageLinkStore;
    private final Environment environment;
    private final MemoryPageCacheBackend memoryBackend;
    private final RedisPageCacheBackend redisBackend;
    private final AtomicLong preloadedPages = new AtomicLong();

    /** Redis 最近一次探测结论与时间戳（避免每次读缓存都探测），以及探测耗时（启动日志用）。 */
    private volatile boolean redisUsable;
    private volatile long redisProbedAt;
    private volatile long lastProbeMillis;

    public PageCacheServiceImpl(SiteSettingsService siteSettingsService,
                                PageCacheProperties properties,
                                PageBodyRenderer pageBodyRenderer,
                                WikiPageService wikiPageService,
                                PageLinkStore pageLinkStore,
                                Environment environment,
                                ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.siteSettingsService = siteSettingsService;
        this.properties = properties;
        this.pageBodyRenderer = pageBodyRenderer;
        this.wikiPageService = wikiPageService;
        this.pageLinkStore = pageLinkStore;
        this.environment = environment;
        this.memoryBackend = new MemoryPageCacheBackend(this::maxEntries);
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
        this.redisBackend = redisTemplate == null || objectMapper == null
                ? null
                : new RedisPageCacheBackend(redisTemplate, objectMapper, this::redisKeyPrefix);
    }

    /** Redis 键前缀（{@code wikikernel.cache.page.redis.key-prefix}；启动时绑定，改完要重启）。 */
    private String redisKeyPrefix() {
        PageCacheProperties.Redis redis = this.properties.getRedis();
        return redis == null ? null : redis.getKeyPrefix();
    }

    // ==================== 启动探测 ====================

    @Override
    public void run(ApplicationArguments args) {
        boolean on = enabledFlag();
        String configured = configuredBackend();
        if (!on) {
            log.info("页面正文缓存：未启用（站点设置 {} 默认 false；可在「特殊:权限配置 → 站点设置」"
                    + "或 LocalSettings.yaml 的 wikikernel.cache.page.* 打开）", ENABLED_KEY);
            return;
        }
        if (BACKEND_NONE.equals(configured)) {
            log.info("页面正文缓存：已关闭（{} = none），页面按实时渲染", BACKEND_KEY);
            return;
        }
        String backend = backendName();
        if (BACKEND_REDIS.equals(backend)) {
            log.info("页面正文缓存：后端 = Redis（{}，探测耗时 {} 毫秒），TTL {} 秒",
                    redisTarget(), this.lastProbeMillis, ttl().toSeconds());
            return;
        }
        log.info("页面正文缓存：后端 = 进程内 LRU（{}），上限 {} 条、TTL {} 秒；"
                        + "页面渲染结果按标题 + 版本号缓存，模板变更会失效并预渲染",
                memoryReason(configured), maxEntries(), ttl().toSeconds());
    }

    // ==================== 读 / 写 ====================

    @Override
    public PageBody body(WikiPage page, String content) {
        long revisionId = page.getLatestRevisionId() == null ? 0L : page.getLatestRevisionId();
        if (!enabled()) {
            return this.pageBodyRenderer.render(page.getTitle(), content, revisionId);
        }
        Optional<PageBody> cached = get(page.getTitle(), revisionId);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageBody rendered = this.pageBodyRenderer.render(page.getTitle(), content, revisionId);
        writeEntry(key(page.getTitle()), rendered);
        return rendered;
    }

    @Override
    public Optional<PageBody> get(String title, Long latestRevisionId) {
        if (!enabled() || latestRevisionId == null || Strings.isBlank(title)) {
            return Optional.empty();
        }
        PageBody cached = readEntry(key(title));
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.revisionId() != latestRevisionId) {
            // 版本已过期（页面被改过）：顺手清掉，永远不会把旧正文返回给调用方
            removeEntry(key(title));
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    @Override
    public Optional<String> html(String title) {
        if (!enabled() || Strings.isBlank(title)) {
            return Optional.empty();
        }
        Long revisionId = this.wikiPageService.getVisibleByTitle(title)
                .map(WikiPage::getLatestRevisionId)
                .orElse(null);
        return get(title, revisionId).map(PageBody::html);
    }

    @Override
    public void put(String title, String html, long revisionId) {
        if (!enabled() || Strings.isBlank(title)) {
            return;
        }
        String key = key(title);
        // 同一版本上覆盖 HTML 时保留渲染元信息（分类 / 显示标题 / 页面属性），否则按只有 HTML 的条目存
        PageBody existing = readEntry(key);
        PageBody body = existing != null && existing.revisionId() == revisionId
                ? existing.withHtml(html)
                : PageBody.of(revisionId, html);
        writeEntry(key, body);
    }

    private void writeEntry(String key, PageBody body) {
        PageCacheBackend backend = selectedBackend();
        if (backend == null) {
            return;
        }
        Duration ttl = ttl();
        try {
            backend.put(key, body, ttl);
        } catch (RuntimeException exception) {
            degrade(exception);
            this.memoryBackend.put(key, body, ttl);
        }
    }

    private PageBody readEntry(String key) {
        PageCacheBackend backend = selectedBackend();
        if (backend == null) {
            return null;
        }
        try {
            return backend.get(key).orElse(null);
        } catch (RuntimeException exception) {
            degrade(exception);
            return this.memoryBackend.get(key).orElse(null);
        }
    }

    private void removeEntry(String key) {
        this.memoryBackend.remove(key);
        if (BACKEND_REDIS.equals(backendName()) && this.redisBackend != null) {
            try {
                this.redisBackend.remove(key);
            } catch (RuntimeException exception) {
                degrade(exception);
            }
        }
    }

    // ==================== 失效 ====================

    @Override
    public void invalidate(String title) {
        if (Strings.isBlank(title)) {
            return;
        }
        removeEntry(key(title));
    }

    @Override
    public void invalidateAll() {
        this.memoryBackend.clear();
        if (BACKEND_REDIS.equals(backendName()) && this.redisBackend != null) {
            try {
                this.redisBackend.clear();
            } catch (RuntimeException exception) {
                degrade(exception);
            }
        }
    }

    @Override
    public void invalidateTranscluding(String templateTitle) {
        if (Strings.isBlank(templateTitle)) {
            return;
        }
        List<String> titles = transcluding(templateTitle);
        if (titles.isEmpty()) {
            return;
        }
        for (String title : titles) {
            invalidate(title);
        }
        log.info("页面正文缓存：模板 {} 变更，已失效 {} 个嵌入页面", templateTitle, titles.size());
        if (preloadOnTemplateChange()) {
            preload(titles);
        }
    }

    /** 嵌入（transclude）了该模板的页面标题（走 core 的模板使用索引，含「调用但还不存在」的模板）。 */
    private List<String> transcluding(String templateTitle) {
        try {
            List<String> titles = this.pageLinkStore.pagesTranscluding(templateTitle, MAX_TRANSCLUDING_PAGES);
            if (titles == null || titles.isEmpty()) {
                return List.of();
            }
            Set<String> unique = new LinkedHashSet<>(titles);
            unique.removeIf(Strings::isBlank);
            return List.copyOf(unique);
        } catch (RuntimeException exception) {
            log.warn("页面正文缓存：查询嵌入 {} 的页面失败：{}", templateTitle, exception.toString());
            return List.of();
        }
    }

    // ==================== 虚拟线程批量预渲染 ====================

    /**
     * 批量预渲染（异步）：调用方立刻返回，渲染在虚拟线程里做。
     * 每个虚拟线程渲染一页（渲染会等数据库 / 模板展开，属于阻塞型任务，正是虚拟线程的用武之地），
     * 并发由 {@link Semaphore} 卡在 {@code cache.page.preload-parallelism}，按
     * {@code cache.page.preload-batch-size} 分批提交，失败只记日志。
     */
    private void preload(List<String> titles) {
        List<String> targets = List.copyOf(titles);
        if (targets.isEmpty()) {
            return;
        }
        Thread.startVirtualThread(() -> runPreload(targets));
    }

    /** 真正跑预渲染（在后台虚拟线程上）：按批提交，批内并发受信号量限制。 */
    private void runPreload(List<String> titles) {
        int parallelism = preloadParallelism();
        int batchSize = preloadBatchSize(parallelism);
        Semaphore permits = new Semaphore(parallelism);
        long startedAt = System.currentTimeMillis();
        int done = 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < titles.size(); start += batchSize) {
                List<String> batch = titles.subList(start, Math.min(titles.size(), start + batchSize));
                List<Future<?>> futures = new ArrayList<>(batch.size());
                for (String title : batch) {
                    // 一个虚拟线程渲染一页；并发由信号量卡在配置的上限
                    futures.add(executor.submit(() -> preloadWithPermit(permits, title)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                        done++;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        log.warn("页面正文缓存：预渲染被中断（已完成 {} 个）", done);
                        return;
                    } catch (ExecutionException exception) {
                        log.warn("页面正文缓存：预渲染任务失败：{}", exception.getCause() == null
                                ? exception.toString() : exception.getCause().toString());
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("页面正文缓存：预渲染批次执行失败：{}", exception.toString());
        } finally {
            log.info("页面正文缓存：模板变更预渲染完成 {} / {} 个页面（并发上限 {}，耗时 {} 毫秒）",
                    done, titles.size(), parallelism, System.currentTimeMillis() - startedAt);
        }
    }

    /** 取到并发许可后预渲染一页（中断 = 放弃这一页，不抛异常）。 */
    private void preloadWithPermit(Semaphore permits, String title) {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            preloadOne(title);
        } finally {
            permits.release();
        }
    }

    /** 预渲染单个页面：重新渲染并写回缓存（页面不存在 / 渲染失败都只记日志）。 */
    private void preloadOne(String title) {
        try {
            WikiPage page = this.wikiPageService.getVisibleByTitle(title).orElse(null);
            if (page == null) {
                return;
            }
            String content = this.wikiPageService.getLatestContent(page.getId()).orElse("");
            body(page, content);
            this.preloadedPages.incrementAndGet();
        } catch (RuntimeException exception) {
            log.warn("页面正文缓存：预渲染页面 {} 失败：{}", title, exception.toString());
        }
    }

    // ==================== 后端选择与降级 ====================

    @Override
    public boolean enabled() {
        return enabledFlag() && !BACKEND_NONE.equals(configuredBackend());
    }

    @Override
    public String backendName() {
        if (!enabled()) {
            return BACKEND_NONE;
        }
        String configured = configuredBackend();
        if (BACKEND_MEMORY.equals(configured)) {
            return BACKEND_MEMORY;
        }
        // auto / redis：连得上才用 Redis，否则退回进程内 LRU（功能照常）
        return redisUsable() ? BACKEND_REDIS : BACKEND_MEMORY;
    }

    @Override
    public long preloadedPages() {
        return this.preloadedPages.get();
    }

    /** 当前真正在用的后端（缓存关闭时为 null）。 */
    private PageCacheBackend selectedBackend() {
        String backend = backendName();
        if (BACKEND_REDIS.equals(backend)) {
            return this.redisBackend;
        }
        if (BACKEND_MEMORY.equals(backend)) {
            return this.memoryBackend;
        }
        return null;
    }

    /** Redis 是否可用（结论在 {@link #REDIS_RETRY_MILLIS} 毫秒内复用，避免每次都探测）。 */
    private boolean redisUsable() {
        if (this.redisBackend == null || !redisConfigured()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (this.redisProbedAt > 0 && now - this.redisProbedAt < REDIS_RETRY_MILLIS) {
            return this.redisUsable;
        }
        boolean usable = probeRedis();
        this.redisUsable = usable;
        this.redisProbedAt = now;
        return usable;
    }

    /**
     * 探测 Redis：放在虚拟线程里做并限时，不可达的主机不会把启动 / 请求线程拖住
     * （连不上、超时都按不可用处理，退回进程内 LRU）。
     */
    private boolean probeRedis() {
        ExecutorService prober = Executors.newVirtualThreadPerTaskExecutor();
        long startedAt = System.currentTimeMillis();
        try {
            Future<Boolean> future = prober.submit(() -> this.redisBackend.available());
            try {
                boolean usable = Boolean.TRUE.equals(future.get(REDIS_PROBE_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS));
                this.lastProbeMillis = System.currentTimeMillis() - startedAt;
                if (!usable) {
                    log.warn("页面正文缓存：Redis（{}）探测未通过，已退回进程内 LRU", redisTarget());
                }
                return usable;
            } catch (TimeoutException exception) {
                future.cancel(true);
                this.lastProbeMillis = System.currentTimeMillis() - startedAt;
                log.warn("页面正文缓存：Redis（{}）探测超时（{} 毫秒），已退回进程内 LRU，"
                                + "{} 秒后重试", redisTarget(), REDIS_PROBE_TIMEOUT_MILLIS,
                        REDIS_RETRY_MILLIS / 1000);
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException exception) {
                this.lastProbeMillis = System.currentTimeMillis() - startedAt;
                log.warn("页面正文缓存：Redis（{}）不可用，已退回进程内 LRU：{}",
                        redisTarget(), rootMessage(exception));
                return false;
            }
        } finally {
            prober.shutdownNow();
        }
    }

    /** Redis 操作失败：记一次日志并退回进程内 LRU（{@link #REDIS_RETRY_MILLIS} 毫秒后再试）。 */
    private void degrade(RuntimeException exception) {
        if (this.redisUsable) {
            log.warn("页面正文缓存：Redis（{}）访问失败，已退回进程内 LRU：{}",
                    redisTarget(), rootMessage(exception));
        }
        this.redisUsable = false;
        this.redisProbedAt = System.currentTimeMillis();
    }

    /** 是否「配置了 Redis」：显式开关，或写了任何 {@code spring.data.redis.*}。 */
    private boolean redisConfigured() {
        PageCacheProperties.Redis redis = this.properties.getRedis();
        if (redis != null && Boolean.TRUE.equals(redis.getEnabled())) {
            return true;
        }
        for (String key : REDIS_PROPERTY_KEYS) {
            if (this.environment.containsProperty(key)) {
                return true;
            }
        }
        return false;
    }

    /** 日志用：Redis 目标地址（读 {@code spring.data.redis.*}）。 */
    private String redisTarget() {
        String host = this.environment.getProperty("spring.data.redis.host", "localhost");
        String port = this.environment.getProperty("spring.data.redis.port", "6379");
        return host + ":" + port;
    }

    /** 日志用：说明为什么退回了进程内 LRU。 */
    private String memoryReason(String configuredBackend) {
        if (BACKEND_MEMORY.equals(configuredBackend)) {
            return "配置为 memory";
        }
        if (!redisConfigured()) {
            return "未配置 Redis";
        }
        return "Redis 连接失败";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // ==================== 配置（每次现读，改完立即生效） ====================

    /** 缓存键：规范化标题（与页面存储 / 查询同一套规则，插件传裸标题也能命中）。 */
    private static String key(String title) {
        return WikiTitles.normalize(title);
    }

    private boolean enabledFlag() {
        return this.siteSettingsService.getBoolean(ENABLED_KEY,
                this.properties.getEnabled() == null ? DEFAULT_ENABLED : this.properties.getEnabled());
    }

    /** 配置的后端名（已归一化；未知值按 auto 处理）。 */
    private String configuredBackend() {
        String value = this.siteSettingsService.get(BACKEND_KEY,
                Strings.trimToEmpty(this.properties.getBackend()));
        if (Strings.isBlank(value)) {
            return DEFAULT_BACKEND;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case BACKEND_MEMORY, BACKEND_REDIS, BACKEND_NONE -> normalized;
            default -> BACKEND_AUTO;
        };
    }

    private Duration ttl() {
        return Duration.ofSeconds(clamp(settingInt(TTL_SECONDS_KEY,
                this.properties.getTtlSeconds(), DEFAULT_TTL_SECONDS), 1, MAX_TTL_SECONDS));
    }

    private int maxEntries() {
        return clamp(settingInt(MAX_ENTRIES_KEY, this.properties.getMaxEntries(), DEFAULT_MAX_ENTRIES),
                1, Integer.MAX_VALUE);
    }

    private boolean preloadOnTemplateChange() {
        return this.siteSettingsService.getBoolean(PRELOAD_ON_TEMPLATE_CHANGE_KEY,
                this.properties.getPreloadOnTemplateChange() == null
                        ? DEFAULT_PRELOAD_ON_TEMPLATE_CHANGE
                        : this.properties.getPreloadOnTemplateChange());
    }

    private int preloadParallelism() {
        return clamp(settingInt(PRELOAD_PARALLELISM_KEY, this.properties.getPreloadParallelism(),
                Runtime.getRuntime().availableProcessors()), 1, MAX_PARALLELISM);
    }

    /** 批量大小：显式配置优先，否则「并发上限 × 8」（下限 {@link #AUTO_BATCH_MIN}）。 */
    private int preloadBatchSize(int parallelism) {
        int configured = settingInt(PRELOAD_BATCH_SIZE_KEY, this.properties.getPreloadBatchSize(), 0);
        if (configured > 0) {
            return clamp(configured, 1, MAX_TRANSCLUDING_PAGES);
        }
        return Math.max(parallelism * AUTO_BATCH_MULTIPLIER, AUTO_BATCH_MIN);
    }

    /** 站点设置优先，缺失 / 非法时用 YAML 兜底，再兜底到内置默认值。 */
    private int settingInt(String key, Integer yamlValue, int defaultValue) {
        int fallback = yamlValue == null ? defaultValue : yamlValue;
        String value = this.siteSettingsService.get(key, "");
        if (Strings.isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
