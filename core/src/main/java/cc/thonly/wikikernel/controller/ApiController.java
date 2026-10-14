package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.ClientIpResolver;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.LoginTokenService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.RateLimiterService;
import cc.thonly.wikikernel.service.RegistrationHookService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.user.LoginTokenCookie;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.RequestEditContext;
import cc.thonly.wikikernel.web.RequestRegistrationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用户相关 API（供登录/注册页面用 fetch 调用）：
 * {@code POST /api/user/login}、{@code POST /api/user/register}、{@code GET /api/user/logout}。
 * <p>
 * 提示文案全部来自 messages*.properties（跟随 {@code ?uselang=}），表单以
 * {@code application/x-www-form-urlencoded} 提交。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final int USERNAME_MAX_LENGTH = 50;
    private static final int PASSWORD_MAX_LENGTH = 128;

    private final UserService userService;
    private final PermissionService permissionService;
    private final FeatureService featureService;
    /** 插件注册前钩子（RegistrationGuard）：注册问答这类验证由插件校验。 */
    private final RegistrationHookService registrationHookService;
    private final LoginTokenService loginTokenService;
    private final LoginTokenCookie loginTokenCookie;
    private final SkinRegistry skinRegistry;
    private final BlockService blockService;
    private final TitleBlacklistService titleBlacklistService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterService rateLimiterService;
    private final MessageSource messageSource;

    public ApiController(UserService userService,
                         PermissionService permissionService,
                         FeatureService featureService,
                         RegistrationHookService registrationHookService,
                         LoginTokenService loginTokenService,
                         LoginTokenCookie loginTokenCookie,
                         SkinRegistry skinRegistry,
                         BlockService blockService,
                         TitleBlacklistService titleBlacklistService,
                         ClientIpResolver clientIpResolver,
                         RateLimiterService rateLimiterService,
                         MessageSource messageSource) {
        this.userService = userService;
        this.permissionService = permissionService;
        this.featureService = featureService;
        this.registrationHookService = registrationHookService;
        this.loginTokenService = loginTokenService;
        this.loginTokenCookie = loginTokenCookie;
        this.skinRegistry = skinRegistry;
        this.blockService = blockService;
        this.titleBlacklistService = titleBlacklistService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiterService = rateLimiterService;
        this.messageSource = messageSource;
    }

    @PostMapping("/user/login")
    public AjaxResult<WikiUser> login(@RequestParam("username") String username,
                                      @RequestParam("password") String password,
                                      @RequestParam(value = "remember", required = false, defaultValue = "0") String remember,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        username = Strings.trimToEmpty(username);
        if (Strings.isBlank(username) || Strings.isBlank(password)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("auth.blankCredentials"));
        }
        // 防字典爆破：按「客户端 IP + 用户名」限流（默认 5 次失败 / 5 分钟 → 锁 5 分钟，见 wikikernel.security.rate-limit）
        String clientKey = clientIpResolver.resolve(request) + "|"
                + username.toLowerCase(java.util.Locale.ROOT);
        RateLimiterService.Decision decision = rateLimiterService.check(RateLimiterService.LOGIN, clientKey);
        if (decision.limited()) {
            return AjaxResult.error(AjaxCode.TOO_MANY_REQUESTS,
                    message("rateLimit.login", decision.retryAfterSeconds()));
        }
        WikiUser wikiUser = userService.login(username, password);
        if (wikiUser == null) {
            rateLimiterService.failure(RateLimiterService.LOGIN, clientKey);
            return AjaxResult.error(message("auth.badCredentials"));
        }
        rateLimiterService.success(RateLimiterService.LOGIN, clientKey);
        UserSession.login(request, wikiUser.toTransferCopy(), "1".equals(remember));
        // 持久登录：先撤掉本浏览器旧令牌，再按“记住我”签发新令牌（不勾选则清掉）
        loginTokenCookie.read(request).ifPresent(loginTokenService::revoke);
        if ("1".equals(remember)) {
            String token = loginTokenService.issue(wikiUser.getId());
            response.addHeader(HttpHeaders.SET_COOKIE,
                    loginTokenCookie.create(token, request.isSecure()).toString());
        } else {
            response.addHeader(HttpHeaders.SET_COOKIE, loginTokenCookie.clear().toString());
        }
        return AjaxResult.success(message("auth.loginSuccess"), wikiUser.toTransferCopy());
    }

    @PostMapping("/user/register")
    public AjaxResult<WikiUser> register(@RequestParam("username") String username,
                                         @RequestParam("password") String password,
                                         @RequestParam(value = "email", required = false) String email,
                                         @RequestParam(value = "timeZone", required = false) String timeZone,
                                         HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        boolean manager = current != null && permissionService.canManage(current.getId());
        if (!featureService.registerEnabled() && !manager) {
            // 注册已关闭：非管理员不能创建账号（页面同样 403）
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("auth.registerDisabled"));
        }
        username = Strings.trimToEmpty(username);
        email = Strings.trimToEmpty(email);
        if (Strings.isBlank(username)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("auth.usernameRequired"));
        }
        // 注册前钩子（RegistrationGuard，如注册问答）：按 order 升序校验，任一 BLOCK 即 400 + 说明
        RegistrationHookService.Decision registerDecision = this.registrationHookService.beforeRegister(
                RequestRegistrationContext.web(request, username, email,
                        RequestEditContext.formParameters(request)));
        if (registerDecision.blocked()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, registerDecision.message());
        }
        if (username.length() > USERNAME_MAX_LENGTH) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("auth.usernameTooLong", USERNAME_MAX_LENGTH));
        }
        if (Strings.isBlank(password)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("auth.passwordRequired"));
        }
        if (password.length() > PASSWORD_MAX_LENGTH) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("auth.passwordTooLong", PASSWORD_MAX_LENGTH));
        }
        if (blockService.createBlocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("block.registerForbidden"));
        }
        // 禁用标题名单：带 <newaccountonly> 的规则会禁止注册这些用户名
        Optional<TitleBlacklistService.Violation> violation = titleBlacklistService.check(
                username, TitleBlacklistService.Operation.NEW_ACCOUNT, null);
        if (violation.isPresent()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    titleBlacklistMessage(violation.get(), username));
        }
        if (userService.isExisted(username)) {
            return AjaxResult.error(AjaxCode.CONFLICT, message("auth.usernameTaken"));
        }
        WikiUser wikiUser = userService.createUser(username, password, email, timeZone);
        if (manager) {
            // 管理员 / 行政员代为创建账号：不切换当前登录
            return AjaxResult.success(message("auth.registerCreatedByAdmin", username), wikiUser.toTransferCopy());
        }
        // 和 MediaWiki 一样：注册成功后直接登录
        UserSession.login(request, wikiUser.toTransferCopy(), false);
        return AjaxResult.success(message("auth.registerSuccess"), wikiUser.toTransferCopy());
    }

    /** 退出登录：清掉 Session 与持久登录令牌后回首页（顶部“退出”链接直接跳这里）。 */
    @GetMapping("/user/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        loginTokenCookie.read(request).ifPresent(loginTokenService::revoke);
        UserSession.logout(request);
        response.addHeader(HttpHeaders.SET_COOKIE, loginTokenCookie.clear().toString());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
    }

    /** 更新用户皮肤偏好（空值 = 用站点默认）；@RestController API。 */
    @PostMapping("/user/skin")
    public AjaxResult<String> updateSkin(@RequestParam(value = "skin", required = false) String skinId,
                                         HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        String value = Strings.trimToEmpty(skinId);
        if (!value.isEmpty() && skinRegistry.byId(value).isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("appearance.invalidSkin"));
        }
        Optional<WikiUser> updated = userService.updateSkin(current.getId(), value);
        if (updated.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        // 立刻刷新会话里的用户副本，皮肤当次请求即生效
        UserSession.refresh(request, updated.get().toTransferCopy());
        return AjaxResult.success(message("appearance.saved"), value);
    }

    /**
     * 修改用户权限（用户页的权限列表保存到这里）。
     * <p>
     * 只有 管理员 / 行政员 可以调用；{@code permissions} 可重复传多个，
     * 未传表示清空该用户的所有权限。
     */
    @PostMapping("/user/permissions")
    public AjaxResult<List<String>> updatePermissions(@RequestParam("username") String username,
                                                      @RequestParam(value = "permissions", required = false) List<String> permissions,
                                                      @RequestParam Map<String, String> parameters,
                                                      HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        boolean allowed = permissionService.canManage(current.getId());
        if (!allowed) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("permissions.forbidden"));
        }

        Optional<WikiUser> target = userService.findByUsername(Strings.trimToEmpty(username));
        if (target.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }

        Set<String> requested = new LinkedHashSet<>(permissions == null ? List.of() : permissions);
        Set<String> known = new LinkedHashSet<>();
        permissionService.allTypes().forEach(type -> known.add(type.getName()));
        for (String name : requested) {
            if (!known.contains(name)) {
                return AjaxResult.error(AjaxCode.BAD_REQUEST, message("permissions.unknownType", name));
            }
        }
        // 每个勾选的用户组可带有效期（表单字段 expiry.<组名>）：空 = 永久，预设 1d/1w/1m/3m/6m/1y
        //（从保存时起算），或 datetime-local 的具体时间（yyyy-MM-ddTHH:mm）
        Map<String, java.time.LocalDateTime> memberships = new java.util.LinkedHashMap<>();
        for (String name : requested) {
            String raw = Strings.trimToEmpty(parameters.get("expiry." + name));
            if (raw.isEmpty()) {
                memberships.put(name, null);
                continue;
            }
            java.time.LocalDateTime expiry = parseExpiry(raw);
            if (expiry == null) {
                return AjaxResult.error(AjaxCode.BAD_REQUEST, message("permissions.invalidExpiry", name));
            }
            memberships.put(name, expiry);
        }

        permissionService.replaceFor(target.get().getId(), memberships, current.getId(),
                Strings.trimToEmpty(parameters.get("reason")));
        return AjaxResult.success(message("permissions.saveSuccess"),
                List.copyOf(permissionService.namesOf(target.get().getId())));
    }

    /** 有效期参数：预设（1d/1w/1m/3m/6m/1y，从保存时起算）或 datetime-local 具体时间；非法返回 null。 */
    private static java.time.LocalDateTime parseExpiry(String value) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return switch (value) {
            case "1d" -> now.plusDays(1);
            case "1w" -> now.plusWeeks(1);
            case "1m" -> now.plusMonths(1);
            case "3m" -> now.plusMonths(3);
            case "6m" -> now.plusMonths(6);
            case "1y" -> now.plusYears(1);
            default -> {
                try {
                    yield java.time.LocalDateTime.parse(value);
                } catch (java.time.format.DateTimeParseException exception) {
                    yield null;
                }
            }
        };
    }

    /** 自定义错误提示（规则里的 {@code errmsg=消息键}）；缺失时回退到内置消息键。 */
    private String titleBlacklistMessage(TitleBlacklistService.Violation violation, String username) {
        String customKey = Strings.trimToEmpty(violation.customMessageKey());
        if (!customKey.isEmpty()) {
            String custom = messageSource.getMessage(customKey, new Object[]{username, violation.pattern()},
                    null, LocaleContextHolder.getLocale());
            if (custom != null) {
                return custom;
            }
        }
        return message("titleblacklist.forbidden.newAccount", username);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
