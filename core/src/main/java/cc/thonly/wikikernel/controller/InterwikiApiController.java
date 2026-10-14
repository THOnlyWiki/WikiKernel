package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiInterwiki;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrls;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨 Wiki 前缀 API（{@code @RestController}）：保存 / 删除 / 导入，
 * 全部需要管理员 / 行政员（与 {@code 特殊:跨Wiki} 页面的可编辑状态一致）。
 */
@RestController
@RequestMapping("/api")
public class InterwikiApiController {

    private final InterwikiService interwikiService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public InterwikiApiController(InterwikiService interwikiService,
                                  PermissionService permissionService,
                                  MessageSource messageSource) {
        this.interwikiService = interwikiService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    /** 新建（{@code id} 留空）或更新一行。 */
    @PostMapping("/interwiki/save")
    public AjaxResult<Map<String, Object>> save(@RequestParam Map<String, String> params,
                                                HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        AjaxResult<Map<String, Object>> denied = requireManager(user);
        if (denied != null) {
            return denied;
        }
        try {
            WikiInterwiki saved = interwikiService.save(parseLong(params.get("id")),
                    new InterwikiService.Form(params.get("prefix"), params.get("url"),
                            params.get("language"),
                            "1".equals(params.get("local")) || "true".equalsIgnoreCase(params.get("local")),
                            "1".equals(params.get("transcludable"))
                                    || "true".equalsIgnoreCase(params.get("transcludable"))),
                    user);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", saved.getId());
            data.put("prefix", saved.getPrefix());
            data.put("redirectUrl", WikiUrls.pagePath("特殊:跨Wiki") + "?saved=1");
            return AjaxResult.success(message("interwiki.saved", saved.getPrefix()), data);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message(exception.getMessage()));
        }
    }

    /** 删除一行。 */
    @PostMapping("/interwiki/delete")
    public AjaxResult<Map<String, Object>> delete(@RequestParam(value = "id", required = false) String id,
                                                 HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        AjaxResult<Map<String, Object>> denied = requireManager(user);
        if (denied != null) {
            return denied;
        }
        boolean removed = interwikiService.delete(parseLong(id));
        if (!removed) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("interwiki.error.notFound"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redirectUrl", WikiUrls.pagePath("特殊:跨Wiki") + "?deleted=1");
        return AjaxResult.success(message("interwiki.deleted"), data);
    }

    /** 从文本导入（每行：前缀 + URL + 可选标志位）。 */
    @PostMapping("/interwiki/import")
    public AjaxResult<Map<String, Object>> importText(@RequestParam(value = "text", required = false) String text,
                                                     HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        AjaxResult<Map<String, Object>> denied = requireManager(user);
        if (denied != null) {
            return denied;
        }
        int count = interwikiService.importText(text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        data.put("redirectUrl", WikiUrls.pagePath("特殊:跨Wiki") + "?saved=1");
        return count == 0
                ? AjaxResult.error(AjaxCode.BAD_REQUEST, message("interwiki.importEmpty"))
                : AjaxResult.success(message("interwiki.imported", count), data);
    }

    // ==================== 工具 ====================

    /** 权限门：非管理员 / 行政员返回错误结果。 */
    private AjaxResult<Map<String, Object>> requireManager(WikiUser user) {
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("auth.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("interwiki.noPermission"));
        }
        return null;
    }

    private static Long parseLong(String value) {
        try {
            return Strings.isBlank(value) ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
