package cc.thonly.wikikernel.plugin.reverseproxy.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.reverseproxy.ReverseProxySupport;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:反向代理} / {@code Special:ReverseProxy}：管理员配置路由。
 *
 * <p>普通用户可以看到这个特殊页，但拿不到路由数据；配置权限沿用 Wiki 的
 * “保护”动作（默认管理员 / 行政员，可在 特殊:权限配置 调整）。</p>
 */
public class ReverseProxySpecialPage implements SpecialPage {

    private final ReverseProxyConfigService config;
    private final ReverseProxySupport support;
    private final AccessCheck accessCheck;
    private final CurrentUser currentUser;

    public ReverseProxySpecialPage(ReverseProxyConfigService config,
                                   ReverseProxySupport support,
                                   AccessCheck accessCheck,
                                   CurrentUser currentUser) {
        this.config = config;
        this.support = support;
        this.accessCheck = accessCheck;
        this.currentUser = currentUser;
    }

    @Override
    public String name() {
        return "反向代理";
    }

    @Override
    public String displayName() {
        return support.message("special.reverseProxy");
    }

    @Override
    public List<String> aliases() {
        return List.of("ReverseProxy", "ProxyRoutes", "Proxy");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/reverse-proxy";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Viewer viewer = currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
        boolean loggedIn = viewer != null && viewer.loggedIn();
        boolean blocked = accessCheck != null && viewer != null
                && accessCheck.blockNotice(viewer).isPresent();
        boolean canManage = loggedIn && !blocked && accessCheck != null
                && accessCheck.can(AccessAction.PROTECT, ReverseProxyConfigService.SPECIAL_TITLE, viewer);
        boolean featureDisabled = !config.enabled();

        model.put("reverseProxyFeatureDisabled", featureDisabled);
        model.put("reverseProxyLoginRequired", !loggedIn);
        model.put("reverseProxyForbidden", loggedIn && !canManage);
        model.put("reverseProxyCanManage", canManage);
        model.put("reverseProxySpecialTitle", ReverseProxyConfigService.SPECIAL_TITLE);
        model.put("reverseProxySaved", "1".equals(request.parameters().get("saved")));
        model.put("reverseProxyDeleted", "1".equals(request.parameters().get("deleted")));

        if (!canManage) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReverseProxyRoute route : config.routes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", route.getSlug());
            row.put("label", route.getLabel());
            row.put("upstream", route.getUpstream());
            row.put("access", route.getAccess());
            row.put("accessLabel", accessLabel(route.getAccess()));
            row.put("enabled", route.isEnabled());
            row.put("rewriteHtml", route.isRewriteHtml());
            row.put("sandbox", route.isSandbox());
            row.put("allowPrivateTargets", route.isAllowPrivateTargets());
            row.put("methods", route.getMethods());
            row.put("timeoutSeconds", route.getTimeoutSeconds());
            row.put("path", route.publicPath());
            row.put("statusLabel", route.isEnabled()
                    ? support.message("reverseProxy.enabled")
                    : support.message("reverseProxy.disabledRoute"));
            rows.add(row);
        }
        model.put("reverseProxyRoutes", rows);
        model.put("reverseProxyEmpty", rows.isEmpty());
    }

    private String accessLabel(String access) {
        return switch (access == null ? "" : access) {
            case ReverseProxyRoute.ACCESS_PUBLIC -> support.message("reverseProxy.access.public");
            case ReverseProxyRoute.ACCESS_LOGIN -> support.message("reverseProxy.access.login");
            default -> support.message("reverseProxy.access.admin");
        };
    }
}
