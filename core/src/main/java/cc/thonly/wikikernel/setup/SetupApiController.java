package cc.thonly.wikikernel.setup;

import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.ConfigCatalog;
import cc.thonly.wikikernel.http.ClientIpResolver;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.RateLimiterService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 安装向导 API（{@code @RestController}）：
 * 状态查询（{@code GET /api/setup/state}）、核心设置（{@code POST /api/setup/core}）、
 * 插件初始化（{@code POST /api/setup/plugin/{id}}，核心未完成时 409）、
 * 完成并重启（{@code POST /api/setup/finish}）。
 * <p>
 * 核心设置包含「缓存（可选）」：页面正文缓存开关 / 后端（auto / redis / memory / none）、
 * Redis 主机 / 端口 / 库号 / 密码 / 键前缀；开关与后端同时写站点设置（立即生效），
 * Redis 连接与键前缀写 {@code LocalSettings.yaml}（下次启动生效）。
 */
@RestController
@RequestMapping("/api/setup")
public class SetupApiController {

    private static final Set<String> DATABASE_TYPES = Set.of("sqlite", "h2", "mysql", "mariadb");
    /** 页面正文缓存后端（与 {@code cache.page.backend} 同一个枚举集合）。 */
    private static final Set<String> CACHE_BACKENDS = Set.of("auto", "redis", "memory", "none");
    private static final String CACHE_BACKEND_DEFAULT = "auto";
    /** Redis 数据库编号上限（Redis 默认 16 个库：0–15）。 */
    private static final int REDIS_DATABASE_MAX = 15;
    /** Redis 键前缀最大长度。 */
    private static final int CACHE_KEY_PREFIX_MAX = 128;
    /** 「下载完整配置」的文件名（Content-Disposition）。 */
    private static final String TEMPLATE_FILENAME = "LocalSettings.full.yaml";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int USERNAME_MAX = 50;

    private final SetupService setupService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterService rateLimiterService;
    private final MessageSource messageSource;
    private final SiteSettingsService siteSettingsService;
    /** 配置全集目录：生成「下载完整配置」的模板（静态文本，不含本机配置）。 */
    private final ConfigCatalog catalog;

    public SetupApiController(SetupService setupService,
                              ClientIpResolver clientIpResolver,
                              RateLimiterService rateLimiterService,
                              MessageSource messageSource,
                              SiteSettingsService siteSettingsService,
                              ConfigCatalog catalog) {
        this.setupService = setupService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiterService = rateLimiterService;
        this.messageSource = messageSource;
        this.siteSettingsService = siteSettingsService;
        this.catalog = catalog;
    }

    /** 安装状态：是否需要安装、核心是否已提交、插件 tab 列表。 */
    @GetMapping("/state")
    public AjaxResult<Map<String, Object>> state(HttpSession session) {
        if (!unlocked(session)) {
            return lockedError();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("required", setupService.isRequired());
        data.put("coreCompleted", setupService.isCoreCompleted());
        data.put("restartEnabled", setupService.isRestartEnabled());
        data.put("plugins", setupService.pluginTabs().stream().map(tab -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tab.get("id"));
            row.put("name", tab.get("name"));
            row.put("hasStep", tab.get("hasStep"));
            row.put("initialized", tab.get("initialized"));
            return row;
        }).toList());
        return AjaxResult.success(data);
    }

    /** 解锁 /init（已安装后）：验证密钥并写入会话，之后可初始化插件；密钥尝试按 IP 限流（防爆破）。 */
    @PostMapping("/unlock")
    public AjaxResult<Void> unlock(@RequestParam(value = "key", required = false) String key,
                                   HttpServletRequest request,
                                   HttpSession session) {
        if (setupService.isRequired()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("setup.unlockNotNeeded"));
        }
        String clientKey = clientIpResolver.resolve(request);
        RateLimiterService.Decision decision = rateLimiterService.check(RateLimiterService.SETUP, clientKey);
        if (decision.limited()) {
            return AjaxResult.error(AjaxCode.TOO_MANY_REQUESTS,
                    message("setup.locked.tooMany", decision.retryAfterSeconds()));
        }
        if (!setupService.keyMatches(key)) {
            rateLimiterService.failure(RateLimiterService.SETUP, clientKey);
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("setup.locked.wrong"));
        }
        rateLimiterService.success(RateLimiterService.SETUP, clientKey);
        session.setAttribute(SetupService.SESSION_UNLOCKED, Boolean.TRUE);
        return AjaxResult.success(message("setup.locked.saved"), null);
    }

    /** 保存核心设置（数据库 / 初始账号 / 邮件 / 功能开关，可空；已初始化时数据库 / 账号可省略 = 保持原样）。 */
    @PostMapping("/core")
    public AjaxResult<Void> saveCore(@RequestParam Map<String, String> params, HttpSession session) {
        if (!unlocked(session)) {
            return lockedError();
        }
        boolean configExists = setupService.hasConfig();
        String dbType = Strings.trimToEmpty(params.get("databaseType")).toLowerCase(Locale.ROOT);
        boolean withDatabase = !dbType.isEmpty();
        if (withDatabase && !DATABASE_TYPES.contains(dbType)) {
            return error("setup.invalidDbType");
        }
        if (!withDatabase && !configExists) {
            return error("setup.invalidDbType");
        }
        String dbFile = Strings.trimToEmpty(params.get("databaseFile"));
        String dbPort = Strings.trimToEmpty(params.get("port"));
        if (withDatabase && !isNetworkDatabase(dbType) && dbFile.isEmpty()) {
            return error("setup.dbFileRequired");
        }
        if (withDatabase && isNetworkDatabase(dbType) && !validPort(dbPort)) {
            return error("setup.invalidPort");
        }
        String adminUsername = Strings.trimToEmpty(params.get("adminUsername"));
        String adminPassword = Strings.trimToEmpty(params.get("adminPassword"));
        boolean withAccount = !adminUsername.isEmpty() || !adminPassword.isEmpty();
        if (withAccount) {
            if (adminUsername.isEmpty() || adminPassword.isEmpty()) {
                return error("setup.adminRequired");
            }
            if (adminUsername.length() > USERNAME_MAX) {
                return error("setup.usernameTooLong");
            }
        } else if (!configExists) {
            // 首次安装必须填初始账号；已初始化后省略 = 保留 YAML 里的账号
            return error("setup.adminRequired");
        }
        String adminEmail = Strings.trimToEmpty(params.get("adminEmail"));
        if (!adminEmail.isEmpty() && !EMAIL.matcher(adminEmail).matches()) {
            return error("setup.invalidEmail");
        }
        boolean emailEnabled = "true".equalsIgnoreCase(Strings.trimToEmpty(params.get("emailEnabled")));
        String emailHost = Strings.trimToEmpty(params.get("emailHost"));
        String emailPort = Strings.trimToEmpty(params.get("emailPort"));
        if (emailEnabled && emailHost.isEmpty()) {
            return error("setup.emailHostRequired");
        }
        if (!emailPort.isEmpty() && !validPort(emailPort)) {
            return error("setup.invalidPort");
        }
        // 站点名称：提交了才改（null = 保持原样），空 = 默认 WikiKernel
        boolean withSiteName = params.containsKey("siteName");
        String siteName = withSiteName ? Strings.trimToEmpty(params.get("siteName")) : null;
        if (withSiteName && siteName.length() > SiteNameService.MAX_LENGTH) {
            return error("setup.siteNameTooLong");
        }

        // 页面正文缓存（可选）：后端必须是四个枚举之一；端口 / 库号 / 键前缀非法直接拒绝，不静默忽略。
        // 没提交后端（老客户端 / 老表单）= 默认 auto；端口 / 库号留空 = 用默认值（6379 / 0）。
        String cacheBackend = Strings.trimToEmpty(params.get("cacheBackend")).toLowerCase(Locale.ROOT);
        if (cacheBackend.isEmpty()) {
            cacheBackend = CACHE_BACKEND_DEFAULT;
        }
        if (!CACHE_BACKENDS.contains(cacheBackend)) {
            return error("setup.cache.invalidBackend");
        }
        boolean cacheEnabled = "true".equalsIgnoreCase(Strings.trimToEmpty(params.get("cacheEnabled")));
        String cacheRedisHost = Strings.trimToEmpty(params.get("cacheRedisHost"));
        String cacheRedisPort = Strings.trimToEmpty(params.get("cacheRedisPort"));
        if (!cacheRedisPort.isEmpty() && !validPort(cacheRedisPort)) {
            return error("setup.invalidPort");
        }
        String cacheRedisDatabase = Strings.trimToEmpty(params.get("cacheRedisDatabase"));
        if (!cacheRedisDatabase.isEmpty() && !validRedisDatabase(cacheRedisDatabase)) {
            return error("setup.cache.invalidDatabase");
        }
        String cacheRedisKeyPrefix = Strings.trimToEmpty(params.get("cacheRedisKeyPrefix"));
        if (!validCacheKeyPrefix(cacheRedisKeyPrefix)) {
            return error("setup.cache.invalidKeyPrefix");
        }
        // Redis 密码只放进配置（不记日志）：留空 = 不用密码
        String cacheRedisPassword = Strings.trimToEmpty(params.get("cacheRedisPassword"));

        setupService.saveCore(new CoreSetup(siteName, dbType, dbFile,
                Strings.trimToEmpty(params.get("host")), dbPort,
                Strings.trimToEmpty(params.get("name")),
                Strings.trimToEmpty(params.get("user")), Strings.trimToEmpty(params.get("password")),
                adminUsername, adminPassword, adminEmail,
                emailEnabled, emailHost, emailPort,
                Strings.trimToEmpty(params.get("emailUsername")),
                Strings.trimToEmpty(params.get("emailPassword")),
                Strings.trimToEmpty(params.get("emailFrom")),
                Strings.trimToEmpty(params.get("emailBaseUrl")),
                "true".equalsIgnoreCase(Strings.trimToEmpty(params.get("register"))),
                "true".equalsIgnoreCase(Strings.trimToEmpty(params.get("typescript"))),
                "true".equalsIgnoreCase(Strings.trimToEmpty(params.get("typescriptCompileOnSave"))),
                cacheEnabled, cacheBackend, cacheRedisHost, cacheRedisPort, cacheRedisDatabase,
                cacheRedisPassword, cacheRedisKeyPrefix));
        if (withSiteName) {
            // 立即生效（不必等重启）：同时写入数据库设置；YAML 里的值在下次启动时再次导入
            siteSettingsService.set(SiteNameService.KEY, SiteNameService.normalize(siteName));
        }
        // 缓存开关与后端立即生效（缓存实现每次读写都重新取站点设置，站点设置优先于 YAML）；
        // Redis 主机 / 端口 / 库号 / 密码 / 键前缀是启动时绑定的，要下次启动才生效。
        siteSettingsService.set(PageCacheService.ENABLED_KEY, Boolean.toString(cacheEnabled));
        siteSettingsService.set(PageCacheService.BACKEND_KEY, cacheBackend);
        return AjaxResult.success(message("setup.saved"), null);
    }

    /** 初始化某插件（核心未完成 → 409；已安装后需要密钥解锁 → 403）。 */
    @PostMapping("/plugin/{id}")
    public AjaxResult<Void> applyPlugin(@PathVariable("id") String id,
                                        @RequestParam Map<String, String> params,
                                        HttpSession session) {
        if (!unlocked(session)) {
            return lockedError();
        }
        try {
            SetupResult result = setupService.applyPlugin(id, params);
            if (result.success()) {
                String text = Strings.trimToEmpty(result.message());
                return AjaxResult.success(text.isEmpty() ? message("setup.saved") : text, null);
            }
            return AjaxResult.error(AjaxCode.BAD_REQUEST, result.message());
        } catch (IllegalStateException exception) {
            return AjaxResult.error(AjaxCode.CONFLICT, message(exception.getMessage()));
        }
    }

    /** 完成安装 / 重新写入配置：写入 LocalSettings.yaml 并（可选）自动重启；返回 /init 密钥。 */
    @PostMapping("/finish")
    public AjaxResult<Map<String, Object>> finish(HttpSession session) {
        if (!unlocked(session)) {
            return lockedError();
        }
        SetupService.FinishResult finishResult;
        try {
            finishResult = setupService.finish();
        } catch (IllegalStateException exception) {
            return AjaxResult.error(AjaxCode.CONFLICT, message(exception.getMessage()));
        }
        if (finishResult.restarted()) {
            Thread exitThread = new Thread(() -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }, "setup-restart");
            exitThread.setDaemon(true);
            exitThread.start();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("restarting", finishResult.restarted());
        data.put("key", finishResult.key());
        String base = message(finishResult.restarted() ? "setup.restarting" : "setup.restartDisabled");
        String text = finishResult.key().isBlank()
                ? base : base + " " + message("setup.keyHint", finishResult.key());
        return AjaxResult.success(text, data);
    }

    /**
     * 下载「完整配置模板」：配置全集里的每一项都带中文说明与默认值（敏感项留空，绝不写明文），
     * 另存为 {@code LocalSettings.yaml} 即可当参考 / 起点使用。
     * <p>
     * 可访问条件与 /init 完全一致（见 {@link #unlocked(HttpSession)}）：未初始化时开放；
     * 已初始化且配置了密钥时必须先解锁，否则 403（不新开泄漏通道）。
     */
    @GetMapping("/config-template")
    public ResponseEntity<Object> configTemplate(HttpSession session) {
        if (!unlocked(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(lockedError());
        }
        // Schema 用「请求所在的站点地址」拼绝对 URL：下载到本机后 IDE 的 YAML 插件才能解析
        String schemaUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(ConfigCatalog.SCHEMA_URL_PATH)
                .toUriString();
        String body = catalog.renderTemplateYaml(schemaUrl);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-yaml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + TEMPLATE_FILENAME + "\"")
                .body(body);
    }

    /** 已安装（不在安装模式）时必须用密钥解锁；未安装 / 未设置密钥时接口自然可用。 */
    private boolean unlocked(HttpSession session) {
        return setupService.isRequired()
                || setupService.activeKey().isEmpty()
                || Boolean.TRUE.equals(session.getAttribute(SetupService.SESSION_UNLOCKED));
    }

    private <T> AjaxResult<T> lockedError() {
        return AjaxResult.error(AjaxCode.FORBIDDEN, message("setup.locked.required"));
    }

    private AjaxResult<Void> error(String key) {
        return AjaxResult.error(AjaxCode.BAD_REQUEST, message(key));
    }

    /** MySQL / MariaDB 都使用 host/port/name/user/password 这组字段，而不是本地文件。 */
    private static boolean isNetworkDatabase(String type) {
        return "mysql".equals(type) || "mariadb".equals(type);
    }

    private static boolean validPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** Redis 库号：0–{@link #REDIS_DATABASE_MAX}（Redis 默认 16 个库）。 */
    private static boolean validRedisDatabase(String value) {
        try {
            int database = Integer.parseInt(value);
            return database >= 0 && database <= REDIS_DATABASE_MAX;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * Redis 键前缀：留空 = 用默认前缀；否则不超过 {@link #CACHE_KEY_PREFIX_MAX} 字符，
     * 且不含空白 / 控制字符（避免 YAML 与日志里出现难以阅读的键）。
     */
    private static boolean validCacheKeyPrefix(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > CACHE_KEY_PREFIX_MAX) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                return false;
            }
        }
        return true;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
