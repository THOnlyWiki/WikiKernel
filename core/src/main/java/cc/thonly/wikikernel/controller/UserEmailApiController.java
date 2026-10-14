package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.mail.EmailConfirmationService;
import cc.thonly.wikikernel.mail.MailException;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户邮件 API（{@code @RestController}）：目前提供「发送邮箱确认邮件」。
 * <p>
 * 发送失败时返回可本地化的错误信息（未配置 SMTP / 总开关关闭 / 该类型被禁用 / 收件地址为空）。
 */
@RestController
@RequestMapping("/api/user/email")
public class UserEmailApiController {

    private final EmailConfirmationService emailConfirmationService;
    private final UserService userService;
    private final MailProperties mailProperties;
    private final BlockService blockService;
    private final MessageSource messageSource;

    public UserEmailApiController(EmailConfirmationService emailConfirmationService,
                                  UserService userService,
                                  MailProperties mailProperties,
                                  BlockService blockService,
                                  MessageSource messageSource) {
        this.emailConfirmationService = emailConfirmationService;
        this.userService = userService;
        this.mailProperties = mailProperties;
        this.blockService = blockService;
        this.messageSource = messageSource;
    }

    /** 给当前登录用户发送邮箱确认邮件。 */
    @PostMapping("/confirm")
    public AjaxResult<Void> sendConfirmation(HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        WikiUser user = userService.findById(current.getId()).orElse(null);
        if (user == null) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("permissions.userNotFound"));
        }
        if (blockService.emailBlocked(user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("block.emailForbidden"));
        }
        try {
            emailConfirmationService.sendConfirmation(user, baseUrl(request));
            return AjaxResult.success(message("email.confirmationSent"), null);
        } catch (MailException exception) {
            return AjaxResult.error(AjaxCode.ERROR, message(exception.messageKey(), exception.args()));
        }
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
