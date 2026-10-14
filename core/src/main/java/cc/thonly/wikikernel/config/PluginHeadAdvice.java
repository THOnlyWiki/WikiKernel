package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.web.RequestHeadContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 站点级 {@code <head>} 注入（插件 {@link HtmlHeadProvider#siteWide()} = true 的扩展点）：
 * 特殊页 / 编辑页 / 历史页等**非词条阅读视图**也会注入，供 Gadgets、多媒体预览、评论区这类
 * 「全站脚本」使用。
 * <p>
 * 词条阅读视图由 {@code WikiPageController} 自己处理（那里有本次渲染的页面属性，SEO 等才能生效），
 * 它会设置请求属性 {@link #HEAD_HANDLED_ATTRIBUTE}，本 advice 随之让路，避免重复注入。
 */
@ControllerAdvice
public class PluginHeadAdvice {

    /** 请求属性：词条阅读视图已在控制器里处理过 head 扩展。 */
    public static final String HEAD_HANDLED_ATTRIBUTE = "wikikernel.pluginHeadHandled";

    private final PluginManager pluginManager;
    private final SiteSettingsService siteSettingsService;
    private final FileService fileService;

    public PluginHeadAdvice(PluginManager pluginManager, SiteSettingsService siteSettingsService,
                            FileService fileService) {
        this.pluginManager = pluginManager;
        this.siteSettingsService = siteSettingsService;
        this.fileService = fileService;
    }

    @ModelAttribute("pluginHeadItems")
    public List<String> pluginHeadItems(HttpServletRequest request) {
        // API 不渲染模板；阅读视图已由控制器处理
        if (request.getRequestURI().startsWith("/api/")
                || request.getAttribute(HEAD_HANDLED_ATTRIBUTE) != null) {
            return List.of();
        }
        // 按客户端依赖（plugin.clientRequires）拓扑顺序取实现：被依赖插件的片段在前
        List<HtmlHeadProvider> providers = this.pluginManager.headProviders(true);
        // 客户端依赖缺失时先留一条 HTML 注释警告（页面照常渲染，不 500）
        List<String> items = new ArrayList<>(this.pluginManager.clientDependencyWarnings());
        if (providers.isEmpty()) {
            return items.isEmpty() ? List.of() : List.copyOf(items);
        }
        HeadContext context = new RequestHeadContext(0, currentTitle(request), queryOf(request), Map.of(), request,
                SiteNameService.normalize(siteSettingsService.get(SiteNameService.KEY, SiteNameService.DEFAULT_NAME)),
                this.fileService);
        for (HtmlHeadProvider provider : providers) {
            try {
                List<String> provided = provider.headItems(context);
                if (provided != null) {
                    items.addAll(provided);
                }
            } catch (RuntimeException ignored) {
                // 插件异常不影响页面渲染
            }
        }
        return items;
    }

    /** 查询参数（字符串视图；同名参数取第一个）。 */
    private static Map<String, String> queryOf(HttpServletRequest request) {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) ->
                query.put(key, values == null || values.length == 0 ? "" : values[0]));
        return query;
    }

    /** 从 URI 模板变量里取当前页面标题（路由为 /wiki/{title} 与 /wiki/{title}/{*rest}）。 */
    private static String currentTitle(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return "";
        }
        Object title = variables.get("title");
        if (title == null) {
            return "";
        }
        String fullTitle = title.toString();
        Object rest = variables.get("rest");
        if (rest != null && !rest.toString().isEmpty()) {
            fullTitle = fullTitle + "/" + rest;
        }
        return fullTitle;
    }
}
