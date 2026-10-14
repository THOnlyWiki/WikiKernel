package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志 API（@RestController）：隐藏 / 显示日志（仅管理员 / 行政员）。
 */
@RestController
@RequestMapping("/api/logs")
public class LogApiController {

    private final LogService logService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public LogApiController(LogService logService,
                            PermissionService permissionService,
                            MessageSource messageSource) {
        this.logService = logService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @PostMapping("/hide")
    public AjaxResult<Long> hide(@RequestParam("id") long id,
                                 @RequestParam(value = "hidden", defaultValue = "true") boolean hidden,
                                 HttpServletRequest request) {
        WikiUser current = UserSession.current(request);
        if (current == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("logs.loginRequired"));
        }
        if (!permissionService.canManage(current.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("logs.forbidden"));
        }
        logService.setHided(id, hidden);
        return AjaxResult.success(message(hidden ? "logs.hideSuccess" : "logs.unhideSuccess"), id);
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
