package cc.thonly.wikikernel.plugin.fingerprintlogin.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintView;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器指纹登录的接口（插件自己的 {@code @RestController}，由 core 动态挂到 MVC）：
 * <pre>
 * POST   /api/fingerprint-login          用浏览器指纹登录（限流；命中则建立会话）
 * GET    /api/fingerprint-login/list     列出**自己**的指纹（需登录）
 * POST   /api/fingerprint-login/add      登记当前浏览器（需登录；同名摘要更新名称）
 * DELETE /api/fingerprint-login/remove   删除自己的某个指纹（需登录）
 * </pre>
 * 返回结构与 core 一致：{@code {code, message, data}}（HTTP 状态一律 200，业务码在 {@code code} 里，
 * 与 core 的 {@code ApiController} 相同约定）。
 * <p>
 * 安全约定：
 * <ul>
 *     <li>登录失败一律给**中性**提示（不透露某个指纹属于谁，也不区分「没登记」与「已存在但用户没了」）；</li>
 *     <li>指纹摘要从不回显（列表只给名称 / 时间 / UA 摘要）；</li>
 *     <li>增删只作用于当前用户自己的行（{@code userId} 取自会话，接口不接受 userId 参数）；</li>
 *     <li>功能关闭时所有接口都返回禁用提示（登录接口在 {@link FingerprintLoginService} 里判）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/fingerprint-login")
public class FingerprintLoginApiController {

    /** 登录接口的「记住我」参数值（与密码登录表单一致：{@code remember=1}）。 */
    private static final String REMEMBER_ON = "1";

    private final FingerprintLoginService service;
    private final FingerprintLoginSupport support;
    private final CurrentUser currentUser;

    public FingerprintLoginApiController(FingerprintLoginService service,
                                         FingerprintLoginSupport support,
                                         CurrentUser currentUser) {
        this.service = service;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 用浏览器指纹登录：命中则建立与密码登录一致的会话；失败给中性提示。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam(value = "fingerprint", required = false) String fingerprint,
            @RequestParam(value = "remember", required = false) String remember,
            HttpServletRequest request) {
        FingerprintLoginService.LoginOutcome outcome = service.login(fingerprint,
                REMEMBER_ON.equals(remember), clientIp(request), isSecure(request));
        if (!outcome.success()) {
            return response(401, outcome.message(), null);
        }
        return response(200, outcome.message(), Map.of("loggedIn", true));
    }

    /** 列出自己的指纹（需登录；管理员也看不到别人的）。 */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        Viewer viewer = viewer();
        if (!viewer.loggedIn()) {
            return response(401, support.message("fingerprint.error.loginRequired"), null);
        }
        if (!support.enabled()) {
            return response(403, support.message("fingerprint.error.disabled"), null);
        }
        return response(200, "", fingerprintsData(viewer.id()));
    }

    /** 登记当前浏览器的指纹（需登录）。 */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(
            @RequestParam(value = "fingerprint", required = false) String fingerprint,
            @RequestParam(value = "label", required = false) String label,
            HttpServletRequest request) {
        Viewer viewer = viewer();
        if (!viewer.loggedIn()) {
            return response(401, support.message("fingerprint.error.loginRequired"), null);
        }
        if (!support.enabled()) {
            return response(403, support.message("fingerprint.error.disabled"), null);
        }
        if (!service.httpsAllowed(isSecure(request))) {
            return response(403, support.message("fingerprint.httpsOnly"), null);
        }
        FingerprintLoginService.AddOutcome outcome;
        try {
            outcome = service.add(viewer.id(), label, fingerprint,
                    request == null ? "" : request.getHeader("User-Agent"));
        } catch (FingerprintLoginService.AddException exception) {
            return addFailure(exception);
        }
        Map<String, Object> data = fingerprintsData(viewer.id());
        data.put("id", outcome.id());
        data.put("updated", outcome.updated());
        String message = outcome.updated()
                ? support.message("fingerprint.add.updated")
                : support.message("fingerprint.add.success", outcome.id());
        return response(200, message, data);
    }

    /** 删除自己的某个指纹。 */
    @DeleteMapping("/remove")
    public ResponseEntity<Map<String, Object>> remove(
            @RequestParam(value = "id", required = false) String id) {
        Viewer viewer = viewer();
        if (!viewer.loggedIn()) {
            return response(401, support.message("fingerprint.error.loginRequired"), null);
        }
        if (!support.enabled()) {
            return response(403, support.message("fingerprint.error.disabled"), null);
        }
        if (!service.remove(viewer.id(), parseId(id))) {
            return response(404, support.message("fingerprint.error.notFound"), null);
        }
        return response(200, support.message("fingerprint.remove.success"), fingerprintsData(viewer.id()));
    }

    /** 列表数据（与特殊页 / 参数设置分区同一批字段；不含指纹摘要）。 */
    private Map<String, Object> fingerprintsData(long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fingerprints", service.list(userId).stream().map(FingerprintView::asModel).toList());
        data.put("max", FingerprintStore.MAX_PER_USER);
        return data;
    }

    private Viewer viewer() {
        return currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
    }

    private ResponseEntity<Map<String, Object>> addFailure(FingerprintLoginService.AddException exception) {
        return switch (exception.error()) {
            case LABEL_REQUIRED -> response(400, support.message("fingerprint.error.labelRequired"), null);
            case LABEL_TOO_LONG -> response(400, support.message("fingerprint.error.labelTooLong",
                    FingerprintLoginSupport.LABEL_MAX_LENGTH), null);
            case HASH_REQUIRED -> response(400, support.message("fingerprint.error.hashRequired"), null);
            case HASH_INVALID -> response(400, support.message("fingerprint.error.hashInvalid"), null);
            case LIMIT_REACHED -> response(409,
                    support.message("fingerprint.maxReached", FingerprintStore.MAX_PER_USER), null);
            // 中性提示：不说明这个指纹属于谁
            case REGISTERED_TO_ANOTHER_USER ->
                    response(409, support.message("fingerprint.error.forbidden"), null);
            case STORE_UNAVAILABLE -> response(500, support.message("fingerprint.error.saveFailed"), null);
        };
    }

    /** 删除参数解析：非法值当作 0（必然删不到 → 404）。 */
    private static long parseId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /** 当前请求是否安全上下文（HTTPS / localhost）：用于「仅 HTTPS 允许」配置。 */
    static boolean isSecure(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        // localhost 也是安全上下文（浏览器把它当 potentially trustworthy origin，crypto.subtle 可用）
        String host = request.getServerName();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    /**
     * 客户端 IP（限流键的一部分）。
     * core 的 {@code ClientInfoFilter} 在 {@code wikikernel.http.trust-proxy-headers=true} 时会包装请求，
     * 因此这里的 {@code RemoteAddr} 在 Docker / CDN 后面也是真实客户端 IP。
     */
    private static String clientIp(HttpServletRequest request) {
        return request == null ? "" : request.getRemoteAddr();
    }

    private static ResponseEntity<Map<String, Object>> response(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
