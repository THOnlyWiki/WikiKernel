package cc.thonly.wikikernel.plugin.gadgets.web;

import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.plugin.gadgets.model.GadgetDefinition;
import cc.thonly.wikikernel.plugin.gadgets.service.GadgetService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gadgets 的个人偏好接口（插件自己的 {@code @RestController}，由 core 动态挂载到 MVC）：
 * {@code POST /api/gadgets/preferences}，参数 {@code gadgets} 可重复。
 * <p>
 * 语义与迁移前一致：勾选的名单写入 {@code wiki_user.gadgets}（逗号分隔）；
 * **一个都没勾 = 存 {@code !}**（明确关闭全部，含默认启用的）；功能关闭时不动用户设置。
 */
@RestController
@RequestMapping("/api/gadgets")
public class GadgetsApiController {

    private final GadgetService gadgets;
    private final UserSettings userSettings;
    private final CurrentUser currentUser;
    private final MessageSource messageSource;

    public GadgetsApiController(GadgetService gadgets, UserSettings userSettings, CurrentUser currentUser,
                                MessageSource messageSource) {
        this.gadgets = gadgets;
        this.userSettings = userSettings;
        this.currentUser = currentUser;
        this.messageSource = messageSource;
    }

    @PostMapping("/preferences")
    public ResponseEntity<Map<String, Object>> save(
            @RequestParam(value = "gadgets", required = false) List<String> selected) {
        if (!currentUser.loggedIn()) {
            return response(401, message("permissions.loginRequired"), null);
        }
        if (!gadgets.enabled()) {
            // 功能关闭：不覆盖用户已保存的偏好（与迁移前一致）
            return response(200, message("gadgets.preferences.disabledNoChange"), null);
        }
        Set<String> known = gadgets.visibleDefinitions().stream()
                .map(GadgetDefinition::name)
                .collect(Collectors.toSet());
        List<String> chosen = selected == null ? List.of() : selected.stream()
                .filter(known::contains).distinct().toList();
        String value = chosen.isEmpty() ? "!" : String.join(",", chosen);
        userSettings.set(currentUser.id(), "gadgets", value);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gadgets", chosen);
        return response(200, message("gadgets.preferences.saved"), data);
    }

    /** 提示文案（core 的 MessageSource 已合并插件消息；漏翻时显示翻译键本身，绝不回落中文）。 */
    private String message(String key) {
        String value = messageSource == null ? null
                : messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
        return value == null ? key : value;
    }

    private static ResponseEntity<Map<String, Object>> response(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
