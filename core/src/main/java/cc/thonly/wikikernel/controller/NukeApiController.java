package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大量删除 API（{@code @RestController}，页面：特殊:大量删除）。
 * <p>
 * 批量软删除页面：仅管理员 / 行政员可用；逐条按 {@code delete} 权限（默认管理员）校验，
 * 受保护页对 canManage 用户等同于放行；删除日志由 {@link WikiPageService#deletePage} 自动记录，不重复记。
 */
@RestController
@RequestMapping("/api/nuke")
public class NukeApiController {

    private final WikiPageService wikiPageService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public NukeApiController(WikiPageService wikiPageService,
                             PermissionService permissionService,
                             AccessControlService accessControlService,
                             MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    /** 批量删除：{@code titles} 可重复（去重后逐条删除），{@code reason} 可空。 */
    @PostMapping("/delete")
    public AjaxResult<Map<String, Object>> delete(
            @RequestParam(value = "titles", required = false) List<String> titles,
            @RequestParam(value = "reason", required = false, defaultValue = "") String reason,
            HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("nuke.noPermission"));
        }

        Set<String> uniqueTitles = new LinkedHashSet<>();
        if (titles != null) {
            for (String title : titles) {
                String value = Strings.trimToEmpty(title);
                if (!value.isEmpty()) {
                    uniqueTitles.add(value);
                }
            }
        }
        if (uniqueTitles.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("nuke.selectedNone"));
        }

        int deleted = 0;
        int failed = 0;
        for (String title : uniqueTitles) {
            try {
                if (accessControlService.can(AccessAction.DELETE, title, user)
                        && wikiPageService.deletePage(user, title) != null) {
                    deleted++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                failed++; // 单条失败（如标题非法）不中断整批
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("failed", failed);
        data.put("reason", Strings.trimToEmpty(reason));
        String text = failed == 0
                ? message("nuke.deleted", deleted)
                : message("nuke.deletedPartial", deleted, failed);
        return AjaxResult.success(text, data);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
