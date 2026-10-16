package cc.thonly.wikikernel.plugin.echo.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotificationStoreJdbc;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotifier;
import cc.thonly.wikikernel.plugin.echo.service.EchoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Echo 通知 API（插件自己的 {@code @RestController}，由 core 动态挂载）：
 * <ul>
 *     <li>{@code GET /api/echo/unread-count}：未读数（顶栏铃铛轮询）；</li>
 *     <li>{@code GET /api/echo/list?limit=15&type=}：通知列表（下拉面板 / 特殊页刷新）；</li>
 *     <li>{@code POST /api/echo/mark-read?id=123}：单条标为已读；</li>
 *     <li>{@code POST /api/echo/mark-all-read}：全部标为已读；</li>
 *     <li>{@code POST /api/echo/preferences}：通知偏好（每类开关，键 {@code echo.pref.enable.<type>}）。</li>
 * </ul>
 * 需要登录（未登录 401）、被封禁 403、功能关闭 403（与 core 其它接口一致）。
 */
@RestController
@RequestMapping("/api/echo")
public class EchoApiController {

    /** 面板 / 列表默认条数。 */
    private static final int DEFAULT_LIMIT = EchoKeys.FLYOUT_LIMIT;

    /** 列表最大条数。 */
    private static final int MAX_LIMIT = 100;

    private final EchoView view;
    private final EchoNotificationStoreJdbc store;
    private final UserSettings userSettings;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final EchoSupport support;
    private final EchoNotifier notifier;

    public EchoApiController(EchoView view, EchoNotificationStoreJdbc store, UserSettings userSettings,
                             AccessCheck access, CurrentUser currentUser, EchoSupport support,
                             EchoNotifier notifier) {
        this.view = view;
        this.store = store;
        this.userSettings = userSettings;
        this.access = access;
        this.currentUser = currentUser;
        this.support = support;
        this.notifier = notifier;
    }

    /** 未读数（铃铛红点；group 可空 = 全部，alert / notice 分组）。 */
    @GetMapping("/unread-count")
    public AjaxResult<Map<String, Object>> unreadCount(@RequestParam(value = "group", required = false) String group) {
        Viewer viewer = viewer();
        Denied denied = deny(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        notifier.ensureWelcome(viewer.id());
        String normalized = normalizeGroup(group);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", normalized == null
                ? view.unreadCount(viewer.id())
                : view.unreadCount(viewer.id(), normalized));
        data.put("alerts", view.unreadCount(viewer.id(), EchoKeys.GROUP_ALERT));
        data.put("notices", view.unreadCount(viewer.id(), EchoKeys.GROUP_NOTICE));
        return AjaxResult.success(data);
    }

    /** 通知列表（最新在前；type 可空 = 全部）。 */
    @GetMapping("/list")
    public AjaxResult<Map<String, Object>> list(@RequestParam(value = "type", required = false) String type,
                                                @RequestParam(value = "group", required = false) String group,
                                                @RequestParam(value = "limit", required = false) Integer limit,
                                                @RequestParam(value = "offset", required = false) Integer offset) {
        Viewer viewer = viewer();
        Denied denied = deny(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int start = offset == null || offset < 0 ? 0 : offset;
        String filter = normalizeType(type);
        String normalizedGroup = normalizeGroup(group);
        List<Map<String, Object>> items = filter != null
                ? view.list(viewer.id(), filter, start, size)
                : normalizedGroup != null
                ? view.listByGroup(viewer.id(), normalizedGroup, start, size)
                : view.list(viewer.id(), (String) null, start, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("unread", normalizedGroup == null
                ? view.unreadCount(viewer.id())
                : view.unreadCount(viewer.id(), normalizedGroup));
        data.put("total", filter != null
                ? store.count(viewer.id(), filter)
                : store.count(viewer.id(), EchoKeys.typesOf(normalizedGroup)));
        return AjaxResult.success(data);
    }

    /** 单条标为已读。 */
    @PostMapping("/mark-read")
    public AjaxResult<Map<String, Object>> markRead(@RequestParam("id") long id) {
        Viewer viewer = viewer();
        Denied denied = deny(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        boolean updated = store.markRead(id, viewer.id());
        if (!updated) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, support.message("echo.notFound"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", view.unreadCount(viewer.id()));
        return AjaxResult.success(support.message("echo.markedRead"), data);
    }

    /** 全部标为已读（可按分组：只清提醒 / 只清一般通知）。 */
    @PostMapping("/mark-all-read")
    public AjaxResult<Map<String, Object>> markAllRead(@RequestParam(value = "group", required = false) String group) {
        Viewer viewer = viewer();
        Denied denied = deny(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        String normalized = normalizeGroup(group);
        store.markAllRead(viewer.id(), EchoKeys.typesOf(normalized));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", view.unreadCount(viewer.id()));
        data.put("alerts", view.unreadCount(viewer.id(), EchoKeys.GROUP_ALERT));
        data.put("notices", view.unreadCount(viewer.id(), EchoKeys.GROUP_NOTICE));
        return AjaxResult.success(support.message("echo.markedAllRead"), data);
    }

    /** 通知偏好：每类一个复选框（未勾选 = 该类关闭）。 */
    @PostMapping("/preferences")
    public AjaxResult<Void> preferences(jakarta.servlet.http.HttpServletRequest request) {
        Viewer viewer = viewer();
        Denied denied = deny(viewer);
        if (denied != null) {
            return AjaxResult.error(denied.code(), denied.message());
        }
        for (String type : EchoKeys.TYPES) {
            boolean enabled = request.getParameter(type) != null;
            userSettings.set(viewer.id(), EchoKeys.PREF_TYPE_PREFIX + type, Boolean.toString(enabled));
        }
        // 邮件通知偏好（opt-in；未勾选 = false）
        userSettings.set(viewer.id(), EchoKeys.PREF_EMAIL,
                Boolean.toString(request.getParameter("email") != null));
        return AjaxResult.success(support.message("echo.preferences.saved"), null);
    }

    // ==================== 内部 ====================

    private Viewer viewer() {
        Viewer viewer = currentUser == null ? null : currentUser.viewer();
        return viewer == null ? Viewer.ANONYMOUS : viewer;
    }

    /** 拒绝访问的原因（null = 放行）。 */
    private Denied deny(Viewer viewer) {
        if (!support.enabled()) {
            return new Denied(AjaxCode.FORBIDDEN, support.message("feature.echoDisabled"));
        }
        if (viewer == null || !viewer.loggedIn()) {
            return new Denied(AjaxCode.UNAUTHORIZED, support.message("permissions.loginRequired"));
        }
        Optional<String> block = access == null ? Optional.empty() : access.blockNotice(viewer);
        return block.map(message -> new Denied(AjaxCode.FORBIDDEN, message)).orElse(null);
    }

    /** 拒绝访问（状态码 + 给用户的消息）。 */
    private record Denied(AjaxCode code, String message) {
    }

    /** 类型参数：只接受已知类型，其余归为「全部」。 */
    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String value = type.trim();
        return EchoKeys.TYPES.contains(value) ? value : null;
    }

    /** 分组参数：{@code alert(s)} / {@code notice(s)}；其余归为「全部」。 */
    private static String normalizeGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String value = group.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        return EchoKeys.GROUP_ALERT.equals(value) || EchoKeys.GROUP_NOTICE.equals(value) ? value : null;
    }
}