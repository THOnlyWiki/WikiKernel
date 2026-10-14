package cc.thonly.wikikernel.setup;

import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.http.ClientIpResolver;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.RateLimiterService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * 安装向导页面（{@code /init}）：独立页面（不套皮肤）。
 * <ul>
 *     <li>未安装：直接显示向导；</li>
 *     <li>已安装：需要 {@code wikikernel.setup.key} 解锁（{@code /init?key=…} 或密钥表单，会话内有效），
 *     解锁后仍可修改**邮件、功能开关与页面正文缓存**（数据库 / 初始账号保持不变，改它们请直接编辑 LocalSettings.yaml），
 *     也可以重新初始化插件 tab（配置合并写回同一份 LocalSettings.yaml）。</li>
 * </ul>
 */
@Controller
public class SetupController {

    private final SetupService setupService;
    private final FeatureService featureService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterService rateLimiterService;
    /** 站点设置（注册问答行编辑器预填用：实现已迁到插件 registration-quiz，core 只读同一批键）。 */
    private final SiteSettingsService siteSettingsService;
    private final MessageSource messageSource;

    public SetupController(SetupService setupService,
                           FeatureService featureService,
                           ClientIpResolver clientIpResolver,
                           RateLimiterService rateLimiterService,
                           SiteSettingsService siteSettingsService,
                           MessageSource messageSource) {
        this.setupService = setupService;
        this.featureService = featureService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiterService = rateLimiterService;
        this.siteSettingsService = siteSettingsService;
        this.messageSource = messageSource;
    }

    /** 本地化文案（找不到键时用兜底）。 */
    private String message(String key, String fallback) {
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    @GetMapping("/init")
    public ModelAndView init(@RequestParam(value = "key", required = false) String key,
                             HttpServletRequest request,
                             HttpSession session) {
        if (setupService.isRequired()) {
            return wizard(false);
        }
        // 没有配置密钥时保持开放（旧版向导生成的配置 / 手动删除了密钥）：下次写入配置会生成并显示密钥
        boolean lockable = !setupService.activeKey().isEmpty();
        boolean unlocked = !lockable
                || Boolean.TRUE.equals(session.getAttribute(SetupService.SESSION_UNLOCKED));
        if (!unlocked && key != null && !key.isBlank()) {
            // 防爆破：密钥尝试按 IP 限流（与 POST /api/setup/unlock 共用计数）
            String clientKey = clientIpResolver.resolve(request);
            RateLimiterService.Decision decision = rateLimiterService.check(RateLimiterService.SETUP, clientKey);
            if (decision.limited()) {
                ModelAndView limited = new ModelAndView("setup/locked");
                limited.addObject("rateLimited", true);
                limited.addObject("retryAfter", decision.retryAfterSeconds());
                return limited;
            }
            if (setupService.keyMatches(key)) {
                rateLimiterService.success(RateLimiterService.SETUP, clientKey);
                session.setAttribute(SetupService.SESSION_UNLOCKED, Boolean.TRUE);
                return new ModelAndView("redirect:/init");
            }
            rateLimiterService.failure(RateLimiterService.SETUP, clientKey);
            ModelAndView locked = new ModelAndView("setup/locked");
            locked.addObject("keyError", true);
            return locked;
        }
        if (!unlocked) {
            return new ModelAndView("setup/locked");
        }
        return wizard(true);
    }

    private ModelAndView wizard(boolean postSetup) {
        ModelAndView modelAndView = new ModelAndView("setup/init");
        modelAndView.addObject("plugins", setupService.pluginTabs());
        modelAndView.addObject("coreCompleted", setupService.isCoreCompleted());
        modelAndView.addObject("postSetup", postSetup);
        modelAndView.addObject("hasKey", !setupService.activeKey().isEmpty());
        modelAndView.addObject("restartEnabled", setupService.isRestartEnabled());
        addCurrentSettings(modelAndView);
        return modelAndView;
    }

    /** /init 预填：现有配置里的邮件与功能开关（数据库 / 初始账号初始化后不再显示，也不预填密码）。 */
    private void addCurrentSettings(ModelAndView modelAndView) {
        Map<String, Object> config = setupService.currentConfig();
        Map<String, Object> wikikernel = mapValue(config.get("wikikernel"));
        Map<String, Object> site = mapValue(wikikernel.get("site"));
        Map<String, Object> email = mapValue(wikikernel.get("email"));
        Map<String, Object> features = mapValue(wikikernel.get("features"));

        modelAndView.addObject("currentSiteName", text(site.get("name")));
        modelAndView.addObject("currentEmailEnabled", truthy(email.get("enabled")));
        modelAndView.addObject("currentEmailHost", text(email.get("host")));
        modelAndView.addObject("currentEmailPort", text(email.get("port")));
        modelAndView.addObject("currentEmailUsername", text(email.get("username")));
        modelAndView.addObject("currentEmailFrom", text(email.get("from")));
        modelAndView.addObject("currentEmailBaseUrl", text(email.get("base-url")));
        modelAndView.addObject("hasEmailPassword", !text(email.get("password")).isBlank());

        // 插件改名（user-interaction → social-profile）：预填时新键优先，旧键（老配置文件）仍接受
        modelAndView.addObject("currentRegister", features.containsKey("register")
                ? truthy(features.get("register"))
                : (!setupService.isCoreCompleted() || featureService.registerEnabled()));
        // 输入框（InputBox）
        // 页面预览（Popups）
        // Flash 播放器（Ruffle）
        // 数学公式（Math）
        // 时间轴（EasyTimeline）
        // 图形（Graph / Vega）
        // TypeScript 服务端编译
        modelAndView.addObject("currentTypescript", features.containsKey("typescript")
                ? truthy(features.get("typescript"))
                : (!setupService.isCoreCompleted() || featureService.typescriptEnabled()));
        modelAndView.addObject("currentTypescriptCompileOnSave",
                features.containsKey("typescript-compile-on-save")
                        ? truthy(features.get("typescript-compile-on-save"))
                        : (!setupService.isCoreCompleted() || featureService.typescriptCompileOnSave()));
        // AI 代理（AI Agent）开关仍在 /init；厂商密钥与「允许普通用户使用」由插件 ai-agent 的 特殊:AI代理?keys=1 管理
        modelAndView.addObject("aiAgentGroup", PermissionService.GROUP_AI_AGENT);

        // 页面正文缓存（Redis 可选）：开关 / 后端以站点设置（运行时真正生效的值）优先、YAML 兜底；
        // Redis 连接与键前缀只有 YAML 里有（写 spring.data.redis.*，下次启动才生效）
        Map<String, Object> cachePage = mapValue(mapValue(wikikernel.get("cache")).get("page"));
        Map<String, Object> cacheRedis = mapValue(cachePage.get("redis"));
        Map<String, Object> springRedis = mapValue(mapValue(mapValue(config.get("spring")).get("data")).get("redis"));
        modelAndView.addObject("currentCacheEnabled",
                siteSettingsService.getBoolean(PageCacheService.ENABLED_KEY, truthy(cachePage.get("enabled"))));
        String cacheBackend = siteSettingsService.get(PageCacheService.BACKEND_KEY, text(cachePage.get("backend")));
        modelAndView.addObject("currentCacheBackend",
                cacheBackend == null || cacheBackend.isBlank() ? "auto" : cacheBackend.trim());
        modelAndView.addObject("currentCacheRedisHost",
                defaultText(springRedis.get("host"), "127.0.0.1"));
        modelAndView.addObject("currentCacheRedisPort",
                defaultText(springRedis.get("port"), "6379"));
        modelAndView.addObject("currentCacheRedisDatabase",
                defaultText(springRedis.get("database"), "0"));
        modelAndView.addObject("currentCacheRedisKeyPrefix",
                defaultText(cacheRedis.get("key-prefix"), PageCacheProperties.DEFAULT_REDIS_KEY_PREFIX));
        modelAndView.addObject("hasCacheRedisPassword", !text(springRedis.get("password")).isBlank());
    }

    /** 列表值渲染成 {@code |} 分隔文本（/init 里图片池 / 白名单 / 免验证组输入框用）。 */
    private static String joinList(Object value) {
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
        }
        return text(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 文本值；空白时用默认值（/init 里 Redis 主机 / 端口 / 库号 / 键前缀的预填）。 */
    private static String defaultText(Object value, String defaultValue) {
        String text = text(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }
}
