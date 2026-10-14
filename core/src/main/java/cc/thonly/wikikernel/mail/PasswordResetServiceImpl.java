package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.data.WikiEmailToken;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 密码重置实现：签发 {@link EmailTypeConfig#PASSWORD_RESET} 令牌并发送重置链接。
 * <p>
 * 发送冷却（{@code wikikernel.email.password-reset-cooldown-seconds}）内重复请求只发一封；
 * 邮件未配置 / 发送失败时静默（对外统一提示，避免暴露账号是否存在）。
 */
@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    private final MailService mailService;
    private final EmailTokenService emailTokenService;
    private final UserService userService;
    private final MailProperties mailProperties;
    private final MessageSource messageSource;
    private final SiteNameService siteNameService;

    public PasswordResetServiceImpl(MailService mailService,
                                    EmailTokenService emailTokenService,
                                    UserService userService,
                                    MailProperties mailProperties,
                                    MessageSource messageSource,
                                    SiteNameService siteNameService) {
        this.mailService = mailService;
        this.emailTokenService = emailTokenService;
        this.userService = userService;
        this.mailProperties = mailProperties;
        this.messageSource = messageSource;
        this.siteNameService = siteNameService;
    }

    @Override
    public void request(String usernameOrEmail, String baseUrl) {
        String value = Strings.trimToEmpty(usernameOrEmail);
        if (value.isEmpty()) {
            return;
        }
        WikiUser user = userService.findByUsername(value)
                .or(() -> userService.findByEmail(value))
                .orElse(null);
        if (user == null) {
            return;
        }
        String email = Strings.trimToEmpty(user.getEmail());
        if (email.isEmpty()) {
            return;
        }
        // 发送冷却：同一账号短时间内只发一封（旧链接仍然有效）
        int cooldownSeconds = mailProperties.getPasswordResetCooldownSeconds();
        if (cooldownSeconds > 0) {
            long remaining = emailTokenService.lastIssuedAt(user.getId(), EmailTypeConfig.PASSWORD_RESET)
                    .map(last -> cooldownSeconds - Duration.between(last, LocalDateTime.now()).getSeconds())
                    .orElse(0L);
            if (remaining > 0) {
                return;
            }
        }
        int hours = Math.max(1, mailProperties.getTokenExpireHours());
        String token = emailTokenService.issue(user.getId(), EmailTypeConfig.PASSWORD_RESET, email, hours);
        String link = Strings.trimToEmpty(baseUrl) + "/password/reset?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            String siteName = siteNameService.name();
            mailService.send(EmailTypeConfig.PASSWORD_RESET, email,
                    message("email.password-reset.subject", siteName),
                    message("email.password-reset.body", siteName, user.getUsername(),
                            String.valueOf(hours), link));
        } catch (MailException exception) {
            // 邮件未配置 / 发送失败：静默（页面提示与成功一致，不暴露账号信息）
        }
    }

    @Override
    public boolean valid(String rawToken) {
        return resolveToken(rawToken).isPresent();
    }

    @Override
    public Result reset(String rawToken, String newPassword) {
        Optional<WikiEmailToken> found = resolveToken(rawToken);
        if (found.isEmpty()) {
            return Result.INVALID;
        }
        WikiEmailToken token = found.get();
        WikiUser user = userService.findById(token.getUserId()).orElse(null);
        if (user == null) {
            return Result.INVALID;
        }
        // 邮箱在签发后被改过：旧链接作废
        if (!token.getEmail().equals(Strings.trimToEmpty(user.getEmail()))) {
            emailTokenService.consume(token);
            return Result.INVALID;
        }
        if (!userService.updatePassword(user.getId(), newPassword)) {
            return Result.INVALID;
        }
        emailTokenService.consume(token);
        return Result.OK;
    }

    /** 解析并校验令牌类型。 */
    private Optional<WikiEmailToken> resolveToken(String rawToken) {
        return emailTokenService.resolve(rawToken)
                .filter(token -> EmailTypeConfig.PASSWORD_RESET.equals(token.getType()));
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
