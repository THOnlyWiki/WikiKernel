package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.data.WikiEmailToken;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.service.PermissionService;
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

@Service
public class EmailConfirmationServiceImpl implements EmailConfirmationService {

    private final MailService mailService;
    private final EmailTokenService emailTokenService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final MailProperties mailProperties;
    private final MessageSource messageSource;
    private final SiteNameService siteNameService;

    public EmailConfirmationServiceImpl(MailService mailService,
                                        EmailTokenService emailTokenService,
                                        UserService userService,
                                        PermissionService permissionService,
                                        MailProperties mailProperties,
                                        MessageSource messageSource,
                                        SiteNameService siteNameService) {
        this.mailService = mailService;
        this.emailTokenService = emailTokenService;
        this.userService = userService;
        this.permissionService = permissionService;
        this.mailProperties = mailProperties;
        this.messageSource = messageSource;
        this.siteNameService = siteNameService;
    }

    @Override
    public void sendConfirmation(WikiUser user, String baseUrl) {
        String email = Strings.trimToEmpty(user.getEmail());
        if (email.isEmpty()) {
            throw new MailException("email.noAddress");
        }
        // 发送冷却：防止被用来反复刷别人的邮箱（单位秒，0 = 不限制）
        int cooldownSeconds = mailProperties.getConfirmCooldownSeconds();
        if (cooldownSeconds > 0) {
            long remaining = userService.emailConfirmLastSentAt(user.getId())
                    .map(last -> cooldownSeconds - Duration.between(last, LocalDateTime.now()).getSeconds())
                    .orElse(0L);
            if (remaining > 0) {
                throw new MailException("email.cooldown", remaining);
            }
        }
        int hours = Math.max(1, mailProperties.getTokenExpireHours());
        String token = emailTokenService.issue(user.getId(), EmailTypeConfig.CONFIRM_EMAIL,
                email, hours);
        String link = Strings.trimToEmpty(baseUrl) + "/email/confirm?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String siteName = siteNameService.name();
        mailService.send(EmailTypeConfig.CONFIRM_EMAIL, email,
                message("email.confirm.subject", siteName),
                message("email.confirm.body", siteName, user.getUsername(),
                        String.valueOf(hours), link));
        // 发送成功才记冷却时间（发送失败不占用冷却）
        userService.markEmailConfirmSent(user.getId());
    }

    @Override
    public Result confirm(String rawToken, Long currentUserId) {
        if (currentUserId == null) {
            return Result.MISMATCH;
        }
        Optional<WikiEmailToken> found = emailTokenService.resolve(rawToken);
        if (found.isEmpty()) {
            return Result.INVALID;
        }
        WikiEmailToken token = found.get();
        if (!EmailTypeConfig.CONFIRM_EMAIL.equals(token.getType())) {
            return Result.INVALID;
        }
        if (!currentUserId.equals(token.getUserId())) {
            return Result.MISMATCH;
        }
        WikiUser user = userService.findById(token.getUserId()).orElse(null);
        if (user == null) {
            return Result.INVALID;
        }
        // 邮箱在签发后被改过：旧链接作废
        if (!token.getEmail().equals(Strings.trimToEmpty(user.getEmail()))) {
            emailTokenService.consume(token);
            return Result.INVALID;
        }
        if (Boolean.TRUE.equals(user.getEmailConfirmed())) {
            emailTokenService.consume(token);
            return Result.ALREADY;
        }
        if (!userService.markEmailConfirmed(user.getId())) {
            return Result.INVALID;
        }
        // 确认成功 → 自动获得「邮箱确认用户」（系统操作，记入权限日志）
        permissionService.grant(user.getId(), PermissionService.GROUP_EMAIL_CONFIRMED, null);
        emailTokenService.consume(token);
        return Result.OK;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
