package cc.thonly.wikikernel.setup;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.ConfigCatalog;
import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.config.SetupProperties;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 安装向导（{@code /init}）核心服务：
 * 收集「核心」tab 与各插件 tab 的设置，完成后合并写入 {@code LocalSettings.yaml} 并（可选）自动重启。
 * <p>
 * 是否需要安装：{@code wikikernel.setup.mode=auto}（默认）时，配置文件不存在即进入安装模式；
 * {@code off} 关闭、{@code force} 强制（测试用）。
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    /** 会话里记录「已用密钥解锁 /init」的属性名。 */
    public static final String SESSION_UNLOCKED = "wikikernel.setup.unlocked";

    /** 缓存后端：需要 Redis 的两个取值（auto 连不上会自动退回内存）。 */
    private static final List<String> REDIS_BACKENDS = List.of("auto", "redis");

    /** Redis 端口默认值。 */
    private static final int DEFAULT_REDIS_PORT = 6379;

    /** Redis 库号默认值。 */
    private static final int DEFAULT_REDIS_DATABASE = 0;

    private static final String HEADER = """
            # ============================================================
            # WikiKernel 本地配置（由 /init 安装向导生成，之后可手动编辑）
            # 优先级：命令行参数 > 环境变量 > LocalSettings.yaml > 内置默认
            # ============================================================
            # 本文件只写「向导里实际配置过的项」，没写的项一律使用内置默认值，不是配置全集。
            # 完整配置项（全部键 + 默认值 + 中文说明）可以在 /init 页面点「下载完整配置」拿到模板。
            # 键名 / 类型的补全与校验由文件第一行的 $schema 提供（IDEA Ultimate、VS Code 的 YAML 扩展）。

            """;

    private final SetupProperties properties;
    private final PluginManager pluginManager;
    private final ObjectProvider<SetupStep> coreSteps;
    private final SetupRestartService restartService;
    private final MessageSource messageSource;
    /** 配置全集目录：只用来给生成的配置文件加 {@code $schema} 指令（完整模板由 /init 的下载接口给出）。 */
    private final ConfigCatalog catalog;

    private final Object lock = new Object();
    private CoreSetup core;
    /** 插件通过 SetupContext.putGlobal 写入的 wikikernel.* 配置（嵌套结构）。 */
    private final Map<String, Object> globalConfig = new LinkedHashMap<>();
    /** 插件私有配置：插件 id → key → value。 */
    private final Map<String, Map<String, Object>> pluginSettings = new LinkedHashMap<>();
    /** 已成功初始化的插件（结果消息）。 */
    private final Map<String, String> initializedPlugins = new LinkedHashMap<>();
    /** 本次运行刚生成的密钥（重启前也能解锁 /init）。 */
    private volatile String generatedKey;

    public SetupService(SetupProperties properties,
                        PluginManager pluginManager,
                        ObjectProvider<SetupStep> coreSteps,
                        SetupRestartService restartService,
                        MessageSource messageSource,
                        ConfigCatalog catalog) {
        this.properties = properties;
        this.pluginManager = pluginManager;
        this.coreSteps = coreSteps;
        this.restartService = restartService;
        this.messageSource = messageSource;
        this.catalog = catalog;
    }

    /** 是否处于安装模式（需要访问 /init）。 */
    public boolean isRequired() {
        String mode = Strings.trimToEmpty(properties.getMode()).toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(mode)) {
            return false;
        }
        if ("force".equals(mode)) {
            return true;
        }
        return !Files.exists(configPath());
    }

    /** 核心设置是否已提交（未提交时插件 tab 不可用）。 */
    public boolean isCoreCompleted() {
        synchronized (lock) {
            return core != null;
        }
    }

    /** 生成的配置文件路径。 */
    public Path configPath() {
        return Path.of(Strings.trimToEmpty(properties.getFile()).isEmpty()
                ? "LocalSettings.yaml" : properties.getFile().trim());
    }

    /** 配置文件是否已存在（= 已完成初始化）。 */
    public boolean hasConfig() {
        return Files.exists(configPath());
    }

    /** 读取现有配置（文件不存在 / 解析失败时为空 map；供 /init 预填当前设置）。 */
    public Map<String, Object> currentConfig() {
        return readExisting(configPath());
    }

    public boolean isRestartEnabled() {
        return properties.isRestartEnabled();
    }

    /** /init 解锁密钥：配置里的（重启后）或本次运行刚生成的（重启前）。 */
    public String activeKey() {
        String configured = Strings.trimToEmpty(properties.getKey());
        if (!configured.isEmpty()) {
            return configured;
        }
        return generatedKey == null ? "" : generatedKey;
    }

    /** 密钥是否匹配（恒定时间比较）。 */
    public boolean keyMatches(String candidate) {
        String key = activeKey();
        if (key.isEmpty() || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 复用 YAML 里的密钥；没有则随机生成一个（24 字节 Base64URL）。 */
    private String ensureKey() {
        String configured = Strings.trimToEmpty(properties.getKey());
        if (!configured.isEmpty()) {
            return configured;
        }
        if (generatedKey == null || generatedKey.isEmpty()) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            generatedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return generatedKey;
    }

    /** 保存核心设置（内存）。 */
    public void saveCore(CoreSetup setup) {
        synchronized (lock) {
            this.core = setup;
        }
    }

    /** 全部安装步骤（core Bean + 插件注册），按 order 排序。 */
    public List<SetupStep> steps() {
        List<SetupStep> result = new ArrayList<>(coreSteps.orderedStream().toList());
        result.addAll(pluginManager.extensions(SetupStep.class));
        result.sort(Comparator.comparingInt(SetupStep::order).thenComparing(SetupStep::id));
        return result;
    }

    /** 某插件的安装步骤（无则空）。 */
    public Optional<SetupStep> findStep(String pluginId) {
        String id = Strings.trimToEmpty(pluginId);
        return steps().stream().filter(step -> step.id().equalsIgnoreCase(id)).findFirst();
    }

    /**
     * 插件 tab 列表：所有已加载插件都有一个入口；注册了 {@link SetupStep} 的插件带表单，
     * 描述文件里声明了功能开关（{@code plugin.featureKey}）而**没有** step 的插件带一个自动开关复选框。
     */
    public List<Map<String, Object>> pluginTabs() {
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (var loaded : pluginManager.loadedPlugins()) {
            PluginDescriptor descriptor = loaded.descriptor();
            SetupStep step = findStep(descriptor.id()).orElse(null);
            List<SetupField> fields = step != null ? step.fields() : featureSwitchField(descriptor);
            Map<String, Object> tab = new LinkedHashMap<>();
            tab.put("id", descriptor.id());
            tab.put("name", descriptor.name());
            tab.put("version", descriptor.version());
            tab.put("description", descriptor.description());
            tab.put("hasStep", step != null || !fields.isEmpty());
            tab.put("stepDescription", step == null ? "" : step.description());
            tab.put("fields", fields);
            tab.put("initialized", initializedPlugins.containsKey(descriptor.id()));
            tabs.add(tab);
        }
        return tabs;
    }

    /** 插件描述文件声明的功能开关（没有 step 的插件）：向导里自动给一个复选框（键固定为 {@code feature}）。 */
    private List<SetupField> featureSwitchField(PluginDescriptor descriptor) {
        if (!descriptor.hasFeatureKey()) {
            return List.of();
        }
        String label = message("plugin." + descriptor.id() + ".name", descriptor.name());
        return List.of(SetupField.checkbox("feature", label, descriptor.featureDefault()));
    }

    /** 执行插件初始化；初始安装且核心未完成时抛 {@code IllegalStateException("setup.coreRequired")}。 */
    public SetupResult applyPlugin(String pluginId, Map<String, String> values) {
        if (!isCoreCompleted() && isRequired()) {
            throw new IllegalStateException("setup.coreRequired");
        }
        String id = Strings.trimToEmpty(pluginId);
        SetupStep step = findStep(id).orElse(null);
        if (step == null) {
            // 没有 step：描述文件里声明了功能开关（plugin.featureKey）时，core 直接把复选框写进 YAML
            //（插件自己用 SiteConfig 读同一个键；core 不认识这个开关属于哪个功能）
            String featureKey = pluginManager.find(id)
                    .map(loaded -> loaded.descriptor().hasFeatureKey()
                            ? loaded.descriptor().featureKey() : "")
                    .orElse("");
            if (featureKey.isEmpty()) {
                return SetupResult.ok("");
            }
            boolean enabled = truthy(values == null ? null : values.get("feature"));
            synchronized (lock) {
                try {
                    putGlobalValue("wikikernel." + featureKey, enabled);
                } catch (IllegalArgumentException exception) {
                    return SetupResult.error(exception.getMessage());
                }
                initializedPlugins.put(id, "");
            }
            return SetupResult.ok("");
        }
        PendingContext context = new PendingContext(id);
        SetupResult result;
        try {
            result = step.apply(values == null ? Map.of() : values, context);
        } catch (RuntimeException exception) {
            log.warn("插件 {} 初始化失败: {}", id, exception.getMessage());
            return SetupResult.error(exception.getMessage());
        }
        if (result == null) {
            return SetupResult.error("no result");
        }
        if (result.success()) {
            synchronized (lock) {
                try {
                    for (Map.Entry<String, Object> entry : context.globals) {
                        putGlobalValue(entry.getKey(), entry.getValue());
                    }
                } catch (IllegalArgumentException exception) {
                    return SetupResult.error(exception.getMessage());
                }
                if (!context.pluginValues.isEmpty()) {
                    pluginSettings.put(id, new LinkedHashMap<>(context.pluginValues));
                }
                initializedPlugins.put(id, result.message());
            }
        }
        return result;
    }

    /** 完成安装的结果：是否已自动重启 + /init 解锁密钥。 */
    public record FinishResult(boolean restarted, String key) {
    }

    /**
     * 完成安装 / 重新写入配置：合并写入 LocalSettings.yaml；成功后按配置自动重启。
     * 核心已完成时写入「核心 + 插件 + 密钥」；核心设置已存在（仅重初始化插件）时只合并「插件 + 密钥」，
     * 已有配置（核心 / 其它插件）不会被覆盖。
     * <p>
     * 写出的文件是「精简配置」：只包含向导这次实际配置过的项（数据库 / 站点名 / 账号 / 邮件 /
     * 功能开关 / 缓存 + 插件写入的配置 + 密钥），没有配置的项由内置默认值兜底。
     * 想要「全字段 + 默认值 + 中文注释」的版本，请从 /init 页面下载完整配置模板
     * （见 {@code GET /api/setup/config-template}）；那份模板不读本机的真实配置，
     * 敏感项一律留空，因此不会泄露密码 / 密钥。
     */
    public FinishResult finish() {
        boolean includeCore = isCoreCompleted();
        if (!includeCore && !Files.exists(configPath())) {
            throw new IllegalStateException("setup.coreRequired");
        }
        String key = ensureKey();
        Path path = configPath();
        try {
            // 顺序即优先级：向导本次提交 > 已有配置里的同名项（deepMerge 的第二个参数覆盖第一个）
            Map<String, Object> merged = deepMerge(readExisting(path), buildConfig(includeCore, key));
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(absolute, catalog.schemaDirective(path) + "\n" + HEADER
                    + catalog.renderConfigYaml(merged), StandardCharsets.UTF_8);
            log.info("安装向导：配置已写入 {}（{} 个顶层键）", absolute, merged.size());
        } catch (IOException | RuntimeException exception) {
            log.error("安装向导：写入配置失败", exception);
            throw new IllegalStateException("setup.writeFailed");
        }
        boolean restarted = properties.isRestartEnabled() && restartService.restart();
        return new FinishResult(restarted, key);
    }

    // ==================== 配置构建 ====================

    private Map<String, Object> buildConfig(boolean includeCore, String key) {
        Map<String, Object> wikikernel = new LinkedHashMap<>();
        Map<String, Object> setupSection = new LinkedHashMap<>();
        setupSection.put("key", key);
        // Redis 连接段（spring.data.redis.*）：只有「启用缓存且后端用 Redis」时才写
        Map<String, Object> redisSection = null;

        if (includeCore) {
            CoreSetup setup;
            synchronized (lock) {
                setup = core;
            }
            // 数据库：只有提交了引擎才写（初始化后不提交 = 保持 YAML 里原有的数据库配置）
            if (!Strings.isBlank(setup.databaseType())) {
                Map<String, Object> database = new LinkedHashMap<>();
                String type = Strings.trimToEmpty(setup.databaseType()).toLowerCase(java.util.Locale.ROOT);
                database.put("type", type);
                if ("mysql".equals(type) || "mariadb".equals(type)) {
                    database.put("host", Strings.trimToEmpty(setup.dbHost()));
                    database.put("port", parseIntOr(setup.dbPort(), 3306));
                    database.put("name", Strings.trimToEmpty(setup.dbName()));
                    database.put("user", Strings.trimToEmpty(setup.dbUser()));
                    database.put("password", Strings.trimToEmpty(setup.dbPassword()));
                } else {
                    database.put("file", Strings.trimToEmpty(setup.databaseFile()));
                }
                wikikernel.put("database", database);
            }

            // 站点名称：提交了才写（null = 保持 YAML 原值）；空 = 默认 WikiKernel
            if (setup.siteName() != null) {
                wikikernel.put("site", Map.of("name", SiteNameService.normalize(setup.siteName())));
            }

            Map<String, Object> email = new LinkedHashMap<>();
            email.put("enabled", setup.emailEnabled());
            if (!Strings.isBlank(setup.emailHost())) {
                email.put("host", setup.emailHost().trim());
                email.put("port", parseIntOr(setup.emailPort(), 587));
            }
            if (!Strings.isBlank(setup.emailUsername())) {
                email.put("username", setup.emailUsername().trim());
            }
            if (!Strings.isBlank(setup.emailPassword())) {
                email.put("password", setup.emailPassword());
            }
            if (!Strings.isBlank(setup.emailFrom())) {
                email.put("from", setup.emailFrom().trim());
            }
            if (!Strings.isBlank(setup.emailBaseUrl())) {
                email.put("base-url", setup.emailBaseUrl().trim());
            }
            wikikernel.put("email", email);

            // 功能开关（用户交互 / 评论 / 多文件上传 / 微件 / 注册 / 滥用过滤器）：写入 wikikernel.features
            Map<String, Object> features = new LinkedHashMap<>();
            // 插件改名（user-interaction → social-profile）：写新键；
            // 读侧两个键都接受（见 FeatureProperties#socialProfile / #userInteraction），旧配置不会被关掉
            features.put("register", setup.register());
            // 输入框（InputBox）
            // 页面预览（Popups）
            // Flash 播放器（Ruffle）
            // 数学公式（Math）
            // 时间轴（EasyTimeline）
            // 图形（Graph / Vega）
            // TypeScript 服务端编译（自包含 GraalJS + 官方 tsc）
            features.put("typescript", setup.typescript());
            features.put("typescript-compile-on-save", setup.typescriptCompileOnSave());
            // AI 代理（AI Agent）：开关在 features，是否放开普通用户在 ai 段
            wikikernel.put("features", features);
            // 注册问答：问题 / 答案两个等长数组（写成 YAML 列表；同时清掉旧格式单问题键）

            // 页面正文缓存（可选）：开关 / 后端写 wikikernel.cache.page.*；
            // Redis 连接（spring.data.redis.*）与键前缀只在「启用且后端要用 Redis」时才写
            Map<String, Object> cachePage = new LinkedHashMap<>();
            cachePage.put("enabled", setup.cacheEnabled());
            String cacheBackend = normalizeCacheBackend(setup.cacheBackend());
            cachePage.put("backend", cacheBackend);
            String cacheRedisHost = Strings.trimToEmpty(setup.cacheRedisHost());
            boolean withRedis = setup.cacheEnabled() && REDIS_BACKENDS.contains(cacheBackend);
            if (withRedis) {
                Map<String, Object> cacheRedis = new LinkedHashMap<>();
                // 显式打开 Redis（连不上仍然自动退回进程内 LRU，业务路径不受影响）
                cacheRedis.put("enabled", true);
                cacheRedis.put("key-prefix",
                        PageCacheProperties.normalizeRedisKeyPrefix(setup.cacheRedisKeyPrefix()));
                cachePage.put("redis", cacheRedis);
            }
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("page", cachePage);
            wikikernel.put("cache", cache);

            // Redis 连接：Spring Boot 标准键（spring.data.redis.*），与页面上其它 Redis 用法共用；
            // 密码只写进配置文件，不写日志。连不上时 auto 会退回进程内 LRU。
            if (withRedis) {
                Map<String, Object> redisConnection = new LinkedHashMap<>();
                redisConnection.put("host", cacheRedisHost.isEmpty() ? "127.0.0.1" : cacheRedisHost);
                redisConnection.put("port", parseIntOr(setup.cacheRedisPort(), DEFAULT_REDIS_PORT));
                redisConnection.put("database",
                        parseIntOr(setup.cacheRedisDatabase(), DEFAULT_REDIS_DATABASE));
                String cacheRedisPassword = Strings.trimToEmpty(setup.cacheRedisPassword());
                if (!cacheRedisPassword.isEmpty()) {
                    redisConnection.put("password", cacheRedisPassword);
                }
                redisSection = Map.of("data", Map.of("redis", redisConnection));
            }

            // 初始账号：初始化后不再提交（保持 YAML 里原有账号不被覆盖）
            if (!Strings.isBlank(setup.adminUsername())) {
                setupSection.put("initial-accounts", buildInitialAccounts(setup));
            }
        }

        wikikernel.put("setup", setupSection);

        if (!pluginSettings.isEmpty()) {
            Map<String, Object> plugins = new LinkedHashMap<>();
            plugins.put("settings", pluginSettings);
            wikikernel.put("plugins", plugins);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("wikikernel", wikikernel);
        if (redisSection != null) {
            root.put("spring", redisSection);
        }
        // 插件 putGlobal 写入的 wikikernel.* 与上面的默认值深度合并（插件值优先）
        return deepMerge(root, globalConfig);
    }

    /** 缓存后端归一化：空 = auto（大小写与四个枚举的校验在 SetupApiController 里已经做过）。 */
    private static String normalizeCacheBackend(String value) {
        String normalized = Strings.trimToEmpty(value);
        return normalized.isEmpty() ? "auto" : normalized;
    }

    /** 初始账号：同时获得「管理员 + 行政员」；密码只存哈希（MediaWiki 格式，不写明文）。 */
    private List<Map<String, Object>> buildInitialAccounts(CoreSetup setup) {
        List<Map<String, Object>> accounts = new ArrayList<>();
        String username = Strings.trimToEmpty(setup.adminUsername());
        if (!username.isEmpty()) {
            accounts.add(account(username, setup.adminPassword(), setup.adminEmail(),
                    List.of(PermissionService.GROUP_ADMIN, PermissionService.GROUP_BUREAUCRAT)));
        }
        return accounts;
    }

    private Map<String, Object> account(String username, String rawPassword, String email, List<String> groups) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("username", username);
        account.put("password-hash", MediaWikiPassword.hash(rawPassword == null ? "" : rawPassword));
        account.put("email", Strings.trimToEmpty(email));
        account.put("groups", groups);
        return account;
    }

    private static int parseIntOr(String value, int defaultValue) {
        try {
            return Integer.parseInt(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /** 把 {@code |} / 换行分隔的输入拆成列表（/init 的横幅图片池、白名单、免验证组用）。 */
    private static List<String> splitList(String value) {
        return java.util.Arrays.stream(Strings.trimToEmpty(value).split("[|\\r\\n]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** 把 {@code wikikernel.a.b} 路径的值写入（或覆盖）嵌套配置。 */
    private void putGlobalValue(String path, Object value) {
        String[] keys = Strings.trimToEmpty(path).split("\\.");
        if (keys.length < 2 || !"wikikernel".equals(keys[0])) {
            throw new IllegalArgumentException(message("setup.error.pluginPath", path));
        }
        Map<String, Object> current = globalConfig;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = current.get(keys[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                current.put(keys[i], child);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) child;
            current = next;
        }
        current.put(keys[keys.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overwrite) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : overwrite.entrySet()) {
            Object existing = result.get(entry.getKey());
            if (existing instanceof Map && entry.getValue() instanceof Map) {
                result.put(entry.getKey(), deepMerge((Map<String, Object>) existing,
                        (Map<String, Object>) entry.getValue()));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readExisting(Path path) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception exception) {
            log.warn("安装向导：读取已有配置失败（将直接写入新配置）: {}", exception.getMessage());
        }
        return Map.of();
    }

    /** 收集插件配置的临时上下文：成功后才真正合并进配置。 */
    private final class PendingContext implements SetupContext {
        private final String pluginId;
        private final List<Map.Entry<String, Object>> globals = new ArrayList<>();
        private final Map<String, Object> pluginValues = new LinkedHashMap<>();

        private PendingContext(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public void putGlobal(String path, Object value) {
            globals.add(new AbstractMap.SimpleEntry<>(Strings.trimToEmpty(path), value));
        }

        @Override
        public void putPluginSetting(String key, Object value) {
            pluginValues.put(Strings.trimToEmpty(key), value);
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 复选框语义：true / 1 / on / yes（大小写不敏感）视为勾选。 */
    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }
}
