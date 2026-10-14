package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

/**
 * 当前请求用户桥实现：从当前 HTTP 请求的会话里取用户，并附上用户组（供插件判断权限）。
 * <p>
 * 结果按请求缓存（同一个请求里多次调用只查一次用户组）；没有请求上下文（后台任务 / 测试）时视为匿名。
 */
@Service
public class CurrentUserImpl implements CurrentUser {

    private static final String ATTRIBUTE = "wikikernel.currentViewer";

    private final PermissionService permissionService;

    public CurrentUserImpl(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public Viewer viewer() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return Viewer.ANONYMOUS;
        }
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof Viewer viewer) {
            return viewer;
        }
        Viewer viewer = resolve(request);
        request.setAttribute(ATTRIBUTE, viewer);
        return viewer;
    }

    private Viewer resolve(HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = permissionService.namesOf(user.getId());
        return new Viewer(user.getId(), user.getUsername(), false, groups,
                groups.contains(PermissionService.GROUP_BOT));
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }
}
