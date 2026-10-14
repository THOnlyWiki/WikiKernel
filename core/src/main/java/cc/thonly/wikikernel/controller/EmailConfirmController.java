package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.mail.EmailConfirmationService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrls;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 邮箱确认链接：{@code GET /email/confirm?token=...}（邮件里的一次性链接）。
 * <p>
 * 未登录时先跳到 {@code 特殊:登录?returnto=...}（登录后自动回到本链接），
 * 登录用户必须与令牌归属一致；成功后自动获得「邮箱确认用户」权限。
 */
@Controller
public class EmailConfirmController {

    private final EmailConfirmationService emailConfirmationService;
    private final MessageSource messageSource;

    public EmailConfirmController(EmailConfirmationService emailConfirmationService,
                                  MessageSource messageSource) {
        this.emailConfirmationService = emailConfirmationService;
        this.messageSource = messageSource;
    }

    @GetMapping("/email/confirm")
    public ModelAndView confirm(@RequestParam(value = "token", required = false) String token,
                                HttpServletRequest request) {
        if (Strings.isBlank(token)) {
            return result("INVALID");
        }
        WikiUser current = UserSession.current(request);
        if (current == null) {
            // 未登录：先去登录，登录后回到本确认链接（MediaWiki 行为）
            String returnTo = "/email/confirm?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            return new ModelAndView("redirect:" + WikiUrls.pagePath("特殊:登录")
                    + "?returnto=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
        }
        EmailConfirmationService.Result outcome =
                emailConfirmationService.confirm(token, current.getId());
        return result(outcome.name());
    }

    private ModelAndView result(String kind) {
        ModelAndView modelAndView = new ModelAndView("email/confirm");
        modelAndView.addObject("title", message("email.confirmTitle"));
        modelAndView.addObject("resultKind", kind);
        modelAndView.addObject("resultText", message(switch (kind) {
            case "OK" -> "email.confirmSuccess";
            case "ALREADY" -> "email.confirmAlready";
            case "MISMATCH" -> "email.confirmMismatch";
            default -> "email.confirmInvalid";
        }));
        return modelAndView;
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
