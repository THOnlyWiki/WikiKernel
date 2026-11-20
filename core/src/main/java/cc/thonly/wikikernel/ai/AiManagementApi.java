package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.ai.*;
import cc.thonly.wikikernel.common.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/ai-management")
public class AiManagementApi {
    private final AiProviderRegistry registry;
    private final AiConfig config;
    private final SiteConfig settings;
    private final CurrentUser current;
    private final AccessCheck access;
    private final PluginDatabase database;
    private final AiTasks tasks;

    public AiManagementApi(AiProviderRegistry registry, AiConfig config, SiteConfig settings,
                           CurrentUser current, AccessCheck access, PluginDatabase database, AiTasks tasks) {
        this.registry = registry; this.config = config; this.settings = settings;
        this.current = current; this.access = access; this.database = database; this.tasks = tasks;
    }

    private void requireManager() {
        if (!AiManagementSupport.isManager(current.viewer()) || access.blockNotice(current.viewer()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理员或行政员可以配置 AI。");
        }
    }

    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    public Object state() {
        requireManager();
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (String id : new TreeSet<>(CoreAiTasks.SCENES)) {
            scenes.add(Map.of("id", id, "enabled", settings.getBoolean("ai.scene." + id + ".enabled", false),
                    "provider", settings.get("ai.scene." + id + ".provider", ""),
                    "groups", settings.get("ai.scene." + id + ".groups", "管理员|行政员")));
        }
        var rows = registry.settingsRows(0);
        rows.forEach(row -> row.put("allowPrivate", settings.getBoolean("ai.provider." + row.get("id") + ".private", false)));
        return Map.of("providers", rows, "scenes", scenes,
                "agent", Map.of("enabled", settings.getBoolean("features.ai-agent", false), "allowNormal", config.settings().allowNormalUsers()),
                "limits", Map.of("concurrency", settings.getInt("ai.max-concurrency", 1),
                        "dailyCalls", settings.getInt("ai.daily-call-limit", 100), "timeout", config.settings().timeoutSeconds()),
                "calls", database.query("SELECT provider, model, outcome, duration_ms, created_at FROM wiki_ai_call ORDER BY id DESC LIMIT 30")
                        .stream().map(DbRow::values).toList());
    }

    @PostMapping("/provider")
    public Object save(@RequestParam Map<String, String> form) {
        requireManager();
        String id = form.getOrDefault("id", "").trim();
        String type = form.getOrDefault("type", "openai");
        String base = form.getOrDefault("baseUrl", "").trim();
        String model = form.getOrDefault("model", "").trim();
        if (!id.matches("[a-z][a-z0-9-]{0,39}") || !Set.of("openai", "gemini", "anthropic").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置标识或协议不合法。");
        }
        boolean privateEndpoint = "true".equals(form.get("allowPrivate"));
        try {
            if (form.getOrDefault("apiKey", "").length() > 1024 || form.getOrDefault("label", "").length() > 80) {
                throw new IllegalArgumentException("密钥或显示名称过长。");
            }
            if (!base.isBlank()) AiEndpointPolicy.validate(base, privateEndpoint);
            if (model.isBlank() || model.length() > 120) throw new IllegalArgumentException("请填写模型名称。");
            database.transaction(() -> {
                config.saveSite(id, blankToNull(form.get("apiKey")), base, model, form.get("proxy"),
                        "true".equals(form.get("clear")));
                settings.set("ai.provider." + id + ".type", type);
                settings.set("ai.provider." + id + ".label", form.getOrDefault("label", id).strip());
                settings.set("ai.provider." + id + ".private", String.valueOf(privateEndpoint));
                var ids = new LinkedHashSet<>(Arrays.asList(settings.get("ai.profiles", "").split(",")));
                ids.remove(""); ids.add(id);
                settings.set("ai.profiles", String.join(",", ids));
                return null;
            });
            return Map.of("saved", true);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    @PostMapping("/scene")
    public Object scene(@RequestParam(value = "id") String id, @RequestParam(value = "provider") String provider,
                         @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                         @RequestParam(value = "groups", defaultValue = "管理员|行政员") String groups) {
        requireManager();
        if (!CoreAiTasks.SCENES.contains(id) || (enabled && registry.selection(0, provider).isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择可用的站点模型。");
        }
        database.transaction(() -> {
            settings.set("ai.scene." + id + ".provider", provider);
            settings.set("ai.scene." + id + ".groups", groups.trim());
            settings.set("ai.scene." + id + ".enabled", String.valueOf(enabled));
            return null;
        });
        return Map.of("saved", true);
    }

    @PostMapping("/test")
    public Object test(@RequestParam(value = "scene") String scene) {
        requireManager();
        return Map.of("result", tasks.complete(scene, current.viewer(), "仅回复 OK。", "连接测试"));
    }

    @PostMapping("/limits")
    public Object limits(@RequestParam("concurrency") int concurrency, @RequestParam("dailyCalls") int dailyCalls,
                          @RequestParam("timeout") int timeout) {
        requireManager();
        if (concurrency < 1 || concurrency > 4 || dailyCalls < 1 || dailyCalls > 10000 || timeout < 5 || timeout > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "并发为 1～4，日请求为 1～10000，超时为 5～300 秒。");
        }
        settings.set("ai.max-concurrency", String.valueOf(concurrency));
        settings.set("ai.daily-call-limit", String.valueOf(dailyCalls));
        settings.set("ai.timeout-seconds", String.valueOf(timeout));
        return Map.of("saved", true);
    }

    @PostMapping("/agent")
    public Object agent(@RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                         @RequestParam(value = "allowNormal", defaultValue = "false") boolean allowNormal) {
        requireManager();
        settings.set("features.ai-agent", String.valueOf(enabled));
        config.saveAllowNormalUsers(allowNormal);
        return Map.of("saved", true);
    }
}
