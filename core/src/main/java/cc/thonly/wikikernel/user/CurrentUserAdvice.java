package cc.thonly.wikikernel.user;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 把所有 MVC 模板都需要的布局模型放进去：
 * <ul>
 *     <li>{@code currentUser}：当前登录用户（未登录为 null），顶部个人工具区用；</li>
 *     <li>{@code userInteractionEnabled} / {@code commentsEnabled}：功能开关（模板据此隐藏入口）；
 *     未读私信数（{@code unreadMessages}）与顶栏「私信」入口已随用户交互迁到插件
 *     {@code social-profile}（{@code PersonalToolbarHook}）；</li>
 *     <li>{@code currentPath}：当前请求路径，页脚语言切换链接用；</li>
 *     <li>{@code assetVersion}：静态资源版本号（随启动变化），CSS/JS 改动重启后浏览器自动重新加载。</li>
 * </ul>
 */
@ControllerAdvice
public class CurrentUserAdvice {

    /** 资源版本：服务启动时刻（同一进程内保持不变）。 */
    private static final String ASSET_VERSION = Long.toHexString(System.currentTimeMillis());

    private final FeatureService featureService;
    private final PermissionService permissionService;

    public CurrentUserAdvice(FeatureService featureService, PermissionService permissionService) {
        this.featureService = featureService;
        this.permissionService = permissionService;
    }

    @ModelAttribute("currentUser")
    public WikiUser currentUser(HttpServletRequest request) {
        return UserSession.current(request);
    }

    /** 注册入口是否可见：功能开启 = 所有人；关闭 = 仅管理员 / 行政员（他们仍可代为创建账号）。 */
    @ModelAttribute("registerAvailable")
    public boolean registerAvailable(HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        return this.featureService.registerEnabled()
                || (user != null && this.permissionService.canManage(user.getId()));
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * 顶栏「登录」链接的 returnto：当前页面（含查询串），并跟随本站页面路径配置
     * （裸根部署时 /wiki/首页 → /首页）。登录页 / 注册页自身返回 /，避免登录后绕回登录页。
     */
    @ModelAttribute("loginReturnTo")
    public String loginReturnTo(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            uri = "/";
        } else {
            // getRequestURI() 是百分号编码形式；先解码，否则 Thymeleaf 会再次编码成 %25E9…
            try {
                uri = java.net.URI.create(uri).getPath();
            } catch (IllegalArgumentException ignored) {
                // 非法 URI 时保留原值，后续仍按站内路径处理。
            }
        }
        String path = WikiUrls.rewriteTemplateLink(uri);
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("特殊:登录") || lower.contains("special:userlogin")
                || lower.contains("特殊:注册") || lower.contains("special:createaccount")
                || lower.contains("特殊:重置密码") || lower.contains("special:passwordreset")) {
            return "/";
        }
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            path = path + "?" + query;
        }
        return path.startsWith("/") && !path.startsWith("//") ? path : "/";
    }

    @ModelAttribute("assetVersion")
    public String assetVersion() {
        return ASSET_VERSION;
    }

    /** 可打印版（{@code ?printable=yes}，仿 MediaWiki）：布局隐藏站点界面，只保留正文。 */
    @ModelAttribute("printableView")
    public boolean printableView(HttpServletRequest request) {
        String value = request.getParameter("printable");
        return value != null && "yes".equalsIgnoreCase(value.trim());
    }
}
