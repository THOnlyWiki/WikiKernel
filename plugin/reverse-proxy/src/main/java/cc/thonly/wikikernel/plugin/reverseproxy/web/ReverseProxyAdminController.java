package cc.thonly.wikikernel.plugin.reverseproxy.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.reverseproxy.ReverseProxySupport;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 反向代理管理 API（仅管理员 / 有保护权限的用户组）。
 *
 * <p>所有写请求都额外校验 {@link ReverseProxyCsrf}：同源过滤 + 会话内随机令牌 +
 * 自定义请求头。这样即使浏览器对 {@code Origin: null} 的处理有缺陷，普通跨站表单
 * 也无法修改代理目标。</p>
 */
@RestController
@RequestMapping("/api/reverse-proxy")
public class ReverseProxyAdminController {

    private final ReverseProxyConfigService config;
    private final AccessCheck accessCheck;
    private final CurrentUser currentUser;
    private final ReverseProxySupport support;

    public ReverseProxyAdminController(ReverseProxyConfigService config,
                                       AccessCheck accessCheck,
                                       CurrentUser currentUser,
                                       ReverseProxySupport support) {
        this.config = config;
        this.accessCheck = accessCheck;
        this.currentUser = currentUser;
        this.support = support;
    }

    /** 管理页加载时取 CSRF 令牌（同时建立会话）。 */
    @GetMapping("/csrf")
    public AjaxResult<Map<String, String>> csrf(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        Viewer viewer = currentUser.viewer();
        AdminDenied denied = adminDenied(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("token", ReverseProxyCsrf.token(request));
        return AjaxResult.success(data);
    }

    @PostMapping("/save")
    public AjaxResult<Map<String, Object>> save(
            @RequestParam("slug") String slug,
            @RequestParam("upstream") String upstream,
            @RequestParam(value = "originalSlug", required = false) String originalSlug,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "access", required = false) String access,
            @RequestParam(value = "methods", required = false) String methods,
            @RequestParam(value = "timeoutSeconds", required = false) String timeoutSeconds,
            @RequestParam(value = "enabled", required = false) String enabled,
            @RequestParam(value = "rewriteHtml", required = false) String rewriteHtml,
            @RequestParam(value = "sandbox", required = false) String sandbox,
            @RequestParam(value = "allowPrivateTargets", required = false) String allowPrivateTargets,
            @RequestHeader(value = ReverseProxyCsrf.HEADER, required = false) String csrf,
            HttpServletRequest request) {
        Viewer viewer = currentUser.viewer();
        AdminDenied denied = adminDenied(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        if (!ReverseProxyCsrf.verify(request, csrf)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("reverseProxy.csrfFailed"));
        }
        int timeout;
        try {
            timeout = timeoutSeconds == null || timeoutSeconds.isBlank()
                    ? ReverseProxyRoute.DEFAULT_TIMEOUT_SECONDS
                    : Integer.parseInt(timeoutSeconds.trim());
        } catch (NumberFormatException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, "超时时间必须是整数");
        }
        ReverseProxyRoute route = new ReverseProxyRoute(
                slug, upstream, access, checked(enabled, true), checked(rewriteHtml, false),
                checked(sandbox, true), methods, timeout, label, checked(allowPrivateTargets, false));
        try {
            ReverseProxyRoute saved = config.upsert(route, originalSlug);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("slug", saved.getSlug());
            data.put("path", saved.publicPath());
            return AjaxResult.success(support.message("reverseProxy.saved"), data);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("reverseProxy.badRequest") + " " + exception.getMessage());
        } catch (RuntimeException exception) {
            return AjaxResult.error(AjaxCode.ERROR,
                    support.message("reverseProxy.operationFailed") + " " + exception.getMessage());
        }
    }

    @PostMapping("/delete")
    public AjaxResult<Void> delete(
            @RequestParam("slug") String slug,
            @RequestHeader(value = ReverseProxyCsrf.HEADER, required = false) String csrf,
            HttpServletRequest request) {
        Viewer viewer = currentUser.viewer();
        AdminDenied denied = adminDenied(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        if (!ReverseProxyCsrf.verify(request, csrf)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("reverseProxy.csrfFailed"));
        }
        if (!config.delete(slug)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, support.message("reverseProxy.notFound"));
        }
        return AjaxResult.success(support.message("reverseProxy.deleted"), null);
    }

    private AdminDenied adminDenied(Viewer viewer) {
        if (viewer == null || !viewer.loggedIn()) {
            return new AdminDenied(AjaxCode.UNAUTHORIZED, support.message("reverseProxy.loginRequired"));
        }
        if (accessCheck != null && accessCheck.blockNotice(viewer).isPresent()) {
            return new AdminDenied(AjaxCode.FORBIDDEN, support.message("reverseProxy.forbidden"));
        }
        if (accessCheck == null
                || !accessCheck.can(AccessAction.PROTECT, ReverseProxyConfigService.SPECIAL_TITLE, viewer)) {
            return new AdminDenied(AjaxCode.FORBIDDEN, support.message("reverseProxy.forbidden"));
        }
        return null;
    }

    private record AdminDenied(AjaxCode code, String message) {
    }

    private static boolean checked(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "on".equalsIgnoreCase(value) || "1".equals(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }
}
