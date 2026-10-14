package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件管理 API：
 * <ul>
 *     <li>{@code GET /api/plugins}：查看当前加载了哪些插件（所有人可看）；</li>
 *     <li>{@code POST /api/plugins/reload}：<b>服务器所有者动态装卸插件</b>（仅管理员 / 行政员）。
 *     重新扫描 {@code wikikernel.plugins.dir}：源 jar 被删除 / 替换的插件自动卸载，新出现的插件自动启动。
 *     加载的一直是 {@code run/.plugins} 下的内容哈希副本，所以替换 / 删除 {@code plugins/} 里的 jar
 *     不会被 JVM 占用（Windows 上不会提示「文件被另一个程序占用」），也<strong>不需要重启应用</strong>。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginManager pluginManager;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public PluginController(PluginManager pluginManager,
                            PermissionService permissionService,
                            MessageSource messageSource) {
        this.pluginManager = pluginManager;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @GetMapping
    public List<PluginInfo> list() {
        return pluginManager.loadedPlugins().stream()
                .map(loaded -> new PluginInfo(
                        loaded.descriptor().id(),
                        loaded.descriptor().name(),
                        loaded.descriptor().version(),
                        loaded.descriptor().description(),
                        loaded.source(),
                        loaded.descriptor().featureKey(),
                        loaded.descriptor().hasFeatureKey() ? loaded.descriptor().featureDefault() : null))
                .toList();
    }

    /**
     * 动态装卸插件（服务器所有者）：见类注释。重复调用是安全的——没有变化时返回空的
     * {@code started} / {@code unloaded}。
     */
    @PostMapping("/reload")
    public AjaxResult<ReloadInfo> reload(HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        if (!permissionService.canManage(user.getId())) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("access.forbidden"));
        }
        PluginManager.ReloadResult result = pluginManager.reload();
        return AjaxResult.success(new ReloadInfo(result.started(), result.unloaded(), result.unavailable(),
                pluginManager.loadedPlugins().size()));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    public record PluginInfo(String id, String name, String version, String description, String source,
                             String featureKey, Boolean featureDefault) {
    }

    /**
     * 动态装卸结果：本次启动 / 卸载的插件 id，没起来的插件 → 原因，以及当前已加载插件总数。
     */
    public record ReloadInfo(List<String> started, List<String> unloaded,
                            Map<String, String> unavailable, int total) {
    }
}
