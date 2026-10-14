package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 访问控制规则 API（{@code @RestController}）：保存 / 删除「目标 + 动作 → 用户组」规则。
 * <p>
 * 只有管理员 / 行政员能修改（防止普通用户给自己提权）；规则立即生效。
 */
@RestController
@RequestMapping("/api/access-rules")
public class AccessRuleApiController {

    private final AccessControlService accessControlService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public AccessRuleApiController(AccessControlService accessControlService,
                                   PermissionService permissionService,
                                   MessageSource messageSource) {
        this.accessControlService = accessControlService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    /**
     * 保存规则：{@code target}（* / 标题 / 前缀* / 特殊:XXX）、{@code action}、
     * {@code groups}（可重复；缺省 = 无人，{@code *} = 所有人）。
     */
    @PostMapping
    public AjaxResult<Map<String, Object>> save(@RequestParam("target") String target,
                                                @RequestParam("action") String action,
                                                @RequestParam(value = "groups", required = false) List<String> groups,
                                                HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("access.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("access.forbidden"));
        }
        Optional<AccessAction> parsedAction = AccessAction.byId(action);
        if (parsedAction.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("access.invalidAction"));
        }
        String normalizedTarget = Strings.trimToEmpty(target);
        if (normalizedTarget.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("access.invalidTarget"));
        }

        List<String> values = groups == null ? List.of() : groups;
        String groupNames;
        if (values.contains(AccessAction.EVERYONE)) {
            groupNames = AccessAction.EVERYONE;
        } else {
            Set<String> validGroups = permissionService.allTypes().stream()
                    .map(WikiPermissionType::getName)
                    .collect(Collectors.toSet());
            List<String> invalid = values.stream()
                    .filter(group -> !validGroups.contains(group))
                    .distinct()
                    .toList();
            if (!invalid.isEmpty()) {
                return AjaxResult.error(AjaxCode.BAD_REQUEST,
                        message("access.invalidGroups", String.join("、", invalid)));
            }
            groupNames = String.join(AccessAction.GROUP_SEPARATOR, values);
        }

        WikiAccessRule rule = accessControlService.saveRule(normalizedTarget, parsedAction.get(), groupNames);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", rule.getId());
        data.put("target", rule.getTarget());
        data.put("action", rule.getAction());
        data.put("groups", rule.getGroupNames());
        return AjaxResult.success(message("access.saved"), data);
    }

    /** 删除规则。 */
    @PostMapping("/delete")
    public AjaxResult<Void> delete(@RequestParam("id") long id, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("access.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("access.forbidden"));
        }
        if (!accessControlService.deleteRule(id)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("access.notFound"));
        }
        return AjaxResult.success(message("access.deleted"), null);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
