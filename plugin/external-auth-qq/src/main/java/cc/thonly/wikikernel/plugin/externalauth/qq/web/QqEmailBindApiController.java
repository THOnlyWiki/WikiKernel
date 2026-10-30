package cc.thonly.wikikernel.plugin.externalauth.qq.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.plugin.externalauth.qq.service.QqEmailBindingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * QQ 邮箱验证绑定 API：
 * <ul>
 *     <li>{@code POST /api/qq-email/bind/request}：登录用户填写数字 QQ 号，发送确认邮件；</li>
 *     <li>{@code GET  /api/qq-email/bind/confirm?token=…}：邮件里的确认链接，成功后写绑定；</li>
 *     <li>{@code POST /api/qq-email/bind/remove}：解除绑定。</li>
 * </ul>
 * 写接口由 core 的 SameOriginWriteFilter 兜底；本页前端带 {@code X-Requested-With}。
 */
@RestController
@RequestMapping("/api/qq-email/bind")
public class QqEmailBindApiController {

    private static final String RATE_LIMIT_CATEGORY = "qq-email-bind";

    private final QqEmailBindingService bindings;
    private final CurrentUser currentUser;
    private final AccessCheck accessCheck;
    private final RateLimit rateLimit;
    private final SiteConfig siteConfig;
    private final Environment environment;
    private final MessageSource messageSource;

    public QqEmailBindApiController(QqEmailBindingService bindings,
                                    CurrentUser currentUser,
                                    AccessCheck accessCheck,
                                    RateLimit rateLimit,
                                    SiteConfig siteConfig,
                                    Environment environment,
                                    MessageSource messageSource) {
        this.bindings = bindings;
        this.currentUser = currentUser;
        this.accessCheck = accessCheck;
        this.rateLimit = rateLimit;
        this.siteConfig = siteConfig;
        this.environment = environment;
        this.messageSource = messageSource;
    }

    /** 发起绑定：QQ 号必须是数字，邮件始终发送到 {@code <qq>@qq.com}。 */
    @PostMapping("/request")
    public AjaxResult<Void> request(@RequestParam(value = "qq", required = false) String qq,
                                    HttpServletRequest request) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<String> block = accessCheck.blockNotice(viewer);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, block.get());
        }
        String key = String.valueOf(viewer.id());
        RateLimit.Decision decision = rateLimit.check(RATE_LIMIT_CATEGORY, key);
        if (decision.limited()) {
            return AjaxResult.error(AjaxCode.TOO_MANY_REQUESTS,
                    message("qqEmail.rateLimited", decision.retryAfterSeconds()));
        }
        try {
            bindings.request(viewer.id(), viewer.name(), qq, baseUrl(request));
            rateLimit.success(RATE_LIMIT_CATEGORY, key);
            return AjaxResult.success(message("qqEmail.sent"), null);
        } catch (IllegalArgumentException exception) {
            rateLimit.hit(RATE_LIMIT_CATEGORY, key);
            String messageKey = Strings.trimToEmpty(exception.getMessage());
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message(
                    messageKey.isEmpty() ? "qqEmail.invalid" : messageKey));
        }
    }

    /** 解除当前账号的 QQ 绑定。 */
    @PostMapping("/remove")
    public AjaxResult<Void> remove() {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        return bindings.unbind(viewer.id())
                ? AjaxResult.success(message("qqEmail.unbound"), null)
                : AjaxResult.error(AjaxCode.NOT_FOUND, message("qqEmail.unbindFailed"));
    }

    /** 邮件确认链接；未登录时先去登录，成功后回到本确认链接。 */
    @GetMapping("/confirm")
    public void confirm(@RequestParam(value = "token", required = false) String token,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            String confirmPath = "/api/qq-email/bind/confirm?token="
                    + URLEncoder.encode(Strings.trimToEmpty(token), StandardCharsets.UTF_8);
            response.sendRedirect(WikiUrls.pagePath("特殊:登录")
                    + "?returnto=" + URLEncoder.encode(confirmPath, StandardCharsets.UTF_8));
            return;
        }
        QqEmailBindingService.ConfirmResult result = bindings.confirm(viewer.id(), token);
        String state = switch (result) {
            case OK -> "success";
            case EXPIRED -> "expired";
            case WRONG_USER -> "wrongUser";
            case TAKEN -> "taken";
            case INVALID -> "invalid";
        };
        response.sendRedirect(WikiUrls.pagePath("特殊:参数设置")
                + "?qqBind=" + URLEncoder.encode(state, StandardCharsets.UTF_8) + "#qq-email");
    }

    /**
     * 邮件链接的站点根地址：优先使用 core 的 {@code wikikernel.email.base-url}（与 core 自己发出的
     * 确认 / 重置邮件一致），其次用第三方登录设置里的外部地址；都留空时按当前请求推导。
     * 反向代理 / 自定义域名时建议配置其中一个。
     */
    private String baseUrl(HttpServletRequest request) {
        String configured = Strings.trimToEmpty(environment.getProperty("wikikernel.email.base-url", ""));
        if (configured.isEmpty()) {
            configured = Strings.trimToEmpty(siteConfig.get("auth.external.base-url", ""));
        }
        if (!configured.isEmpty()) {
            while (configured.endsWith("/")) {
                configured = configured.substring(0, configured.length() - 1);
            }
            return configured;
        }
        String scheme = Strings.trimToEmpty(request.getScheme());
        if (scheme.isEmpty()) {
            scheme = "http";
        }
        String host = Strings.trimToEmpty(request.getServerName());
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = request.getServerPort();
        String portPart = (port == 80 && "http".equalsIgnoreCase(scheme))
                || (port == 443 && "https".equalsIgnoreCase(scheme)) ? "" : ":" + port;
        String context = Strings.trimToEmpty(request.getContextPath());
        if (context.endsWith("/")) {
            context = context.substring(0, context.length() - 1);
        }
        return scheme + "://" + host + portPart + context;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
