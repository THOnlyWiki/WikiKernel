package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.http.ClientIpResolver;
import cc.thonly.wikikernel.mail.PasswordResetService;
import cc.thonly.wikikernel.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * 忘记密码（仿 MediaWiki 的 {@code Special:PasswordReset}）：
 * <ul>
 *     <li>{@code POST /password/reset-request}：特殊:重置密码 页的表单（用户名 / 邮箱）→ 发送重置链接（同页提示，
 *     不暴露账号是否存在），按 IP 限流防止刷邮件；</li>
 *     <li>{@code GET /password/reset?token=…}：邮件里的链接 → 设置新密码表单；</li>
 *     <li>{@code POST /password/reset}：保存新密码（一次性令牌；提交不再次限流，令牌本身不可枚举）。</li>
 * </ul>
 */
@Controller
public class PasswordResetController {

    /** 与注册一致的密码长度上限。 */
    private static final int PASSWORD_MAX_LENGTH = 128;

    private final PasswordResetService passwordResetService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterService rateLimiterService;
    private final MailProperties mailProperties;
    private final MessageSource messageSource;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   ClientIpResolver clientIpResolver,
                                   RateLimiterService rateLimiterService,
                                   MailProperties mailProperties,
                                   MessageSource messageSource) {
        this.passwordResetService = passwordResetService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiterService = rateLimiterService;
        this.mailProperties = mailProperties;
        this.messageSource = messageSource;
    }

    /** 特殊:重置密码 页的表单提交：发送重置邮件（无论账号是否存在，提示一致）。 */
    @PostMapping("/password/reset-request")
    public ModelAndView requestReset(@RequestParam(value = "username", required = false) String username,
                                     HttpServletRequest request) {
        ModelAndView modelAndView = requestPage();
        String clientKey = clientIpResolver.resolve(request);
        RateLimiterService.Decision decision =
                rateLimiterService.check(RateLimiterService.PASSWORD_RESET, clientKey);
        if (decision.limited()) {
            modelAndView.addObject("rateLimited", true);
            modelAndView.addObject("retryAfter", decision.retryAfterSeconds());
            return modelAndView;
        }
        passwordResetService.request(username, baseUrl(request));
        rateLimiterService.hit(RateLimiterService.PASSWORD_RESET, clientKey);
        modelAndView.addObject("sent", true);
        return modelAndView;
    }

    /** 邮件里的重置链接：打开设置新密码表单（无效令牌显示提示页）。 */
    @GetMapping("/password/reset")
    public ModelAndView form(@RequestParam(value = "token", required = false) String token) {
        String value = Strings.trimToEmpty(token);
        ModelAndView modelAndView = resetPage();
        modelAndView.addObject("token", value);
        modelAndView.addObject("state", passwordResetService.valid(value) ? "form" : "invalid");
        return modelAndView;
    }

    /** 保存新密码。 */
    @PostMapping("/password/reset")
    public ModelAndView submit(@RequestParam(value = "token", required = false) String token,
                               @RequestParam(value = "password", required = false) String password,
                               @RequestParam(value = "confirmPassword", required = false) String confirmPassword) {
        String value = Strings.trimToEmpty(token);
        ModelAndView modelAndView = resetPage();
        modelAndView.addObject("token", value);
        if (!passwordResetService.valid(value)) {
            modelAndView.addObject("state", "invalid");
            return modelAndView;
        }
        String rawPassword = password == null ? "" : password;
        if (Strings.isBlank(rawPassword)) {
            return resetFormError(modelAndView, message("passwordReset.blank"));
        }
        if (rawPassword.length() > PASSWORD_MAX_LENGTH) {
            return resetFormError(modelAndView,
                    message("passwordReset.tooLong", PASSWORD_MAX_LENGTH));
        }
        if (!rawPassword.equals(Strings.trimToEmpty(confirmPassword))) {
            return resetFormError(modelAndView, message("passwordReset.mismatch"));
        }
        PasswordResetService.Result result = passwordResetService.reset(value, rawPassword);
        modelAndView.addObject("state", result == PasswordResetService.Result.OK ? "success" : "invalid");
        return modelAndView;
    }

    // ==================== 内部 ====================

    private ModelAndView requestPage() {
        ModelAndView modelAndView = new ModelAndView("special/password-reset");
        modelAndView.addObject("title", message("special.passwordReset"));
        return modelAndView;
    }

    private ModelAndView resetPage() {
        ModelAndView modelAndView = new ModelAndView("email/password-reset");
        modelAndView.addObject("title", message("special.passwordReset"));
        return modelAndView;
    }

    private ModelAndView resetFormError(ModelAndView modelAndView, String error) {
        modelAndView.addObject("state", "form");
        modelAndView.addObject("error", error);
        return modelAndView;
    }

    /** 站点基础地址：优先用配置的 {@code wikikernel.email.base-url}，否则按当前请求推导。 */
    private String baseUrl(HttpServletRequest request) {
        if (!Strings.isBlank(mailProperties.getBaseUrl())) {
            String base = mailProperties.getBaseUrl().trim();
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
