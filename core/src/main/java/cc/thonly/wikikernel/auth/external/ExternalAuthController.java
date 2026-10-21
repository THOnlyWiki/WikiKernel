package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthException;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.LoginTokenService;
import cc.thonly.wikikernel.user.LoginTokenCookie;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 第三方登录入口 / 回调 / 解绑。
 * <p>
 * 固定路由放在 {@code /auth/{provider}/...}，不经过页面访问规则；真正的权限判断在流程内：
 * 绑定要求已登录，解绑要求当前用户 + session 里的 CSRF token。
 */
@Controller
public class ExternalAuthController {

    private final ExternalAuthService externalAuthService;
    private final LoginTokenService loginTokenService;
    private final LoginTokenCookie loginTokenCookie;
    private final MessageSource messageSource;

    public ExternalAuthController(ExternalAuthService externalAuthService,
                                  LoginTokenService loginTokenService,
                                  LoginTokenCookie loginTokenCookie,
                                  MessageSource messageSource) {
        this.externalAuthService = externalAuthService;
        this.loginTokenService = loginTokenService;
        this.loginTokenCookie = loginTokenCookie;
        this.messageSource = messageSource;
    }

    @GetMapping("/auth/{provider}/start")
    public void start(@PathVariable("provider") String provider,
                      @RequestParam(value = "link", required = false) String link,
                      @RequestParam(value = "returnto", required = false) String returnTo,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        try {
            String target = externalAuthService.start(provider,
                    "1".equals(link) || "true".equalsIgnoreCase(link), returnTo, request);
            response.sendRedirect(target);
        } catch (ExternalAuthException exception) {
            redirectError(request, response, exception.getMessage(),
                    WikiUrls.pagePath("特殊:登录"));
        }
    }

    @GetMapping("/auth/{provider}/callback")
    public void callback(@PathVariable("provider") String provider,
                         @RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error", required = false) String error,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try {
            ExternalAuthService.Result result = externalAuthService.callback(
                    provider, code, error, state, request);
            if (result.user() != null) {
                login(request, response, result.user());
            }
            response.sendRedirect(result.redirectTo());
        } catch (ExternalAuthException exception) {
            redirectError(request, response, exception.getMessage(),
                    WikiUrls.pagePath("特殊:登录"));
        } catch (RuntimeException exception) {
            redirectError(request, response, message("auth.external.failed"),
                    WikiUrls.pagePath("特殊:登录"));
        }
    }

    @GetMapping("/auth/{provider}/unlink")
    public void unlink(@PathVariable("provider") String provider,
                       @RequestParam(value = "token", required = false) String token,
                       @RequestParam(value = "returnto", required = false) String returnTo,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        WikiUser current = UserSession.current(request);
        String preferences = WikiUrls.pagePath("特殊:参数设置");
        if (current == null) {
            redirectError(request, response, message("auth.external.linkLoginRequired"),
                    WikiUrls.pagePath("特殊:登录"));
            return;
        }
        String target = ExternalAuthSettings.safeReturnTo(returnTo, preferences);
        if (!externalAuthService.verifyUnlinkToken(request, token)) {
            redirectError(request, response, message("auth.external.unlinkInvalid"), target);
            return;
        }
        if (!externalAuthService.unlink(current.getId(), provider)) {
            redirectError(request, response, message("auth.external.notLinked"), target);
            return;
        }
        String redirect = appendQuery(target, "external=unlinked", "provider=" + provider);
        if (target.equals(preferences)) {
            redirect += "#external";
        }
        response.sendRedirect(redirect);
    }

    private void login(HttpServletRequest request, HttpServletResponse response, WikiUser user) {
        UserSession.login(request, user.toTransferCopy(), true);
        loginTokenCookie.read(request).ifPresent(loginTokenService::revoke);
        String token = loginTokenService.issue(user.getId());
        response.addHeader(HttpHeaders.SET_COOKIE,
                loginTokenCookie.create(token, request.isSecure()).toString());
    }

    private void redirectError(HttpServletRequest request, HttpServletResponse response,
                               String message, String target) throws IOException {
        String text = message == null || message.isBlank() ? message("auth.external.failed") : message;
        response.sendRedirect(appendQuery(target,
                "external_error=" + URLEncoder.encode(text, StandardCharsets.UTF_8)));
    }

    private static String appendQuery(String url, String... pairs) {
        StringBuilder out = new StringBuilder(url);
        out.append(url.contains("?") ? '&' : '?');
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                out.append('&');
            }
            out.append(pairs[index]);
        }
        return out.toString();
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}

