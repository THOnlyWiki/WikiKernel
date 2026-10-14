package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.service.PermissionService;

import java.util.Set;

/**
 * {@link Viewer} 构造助手：把登录用户（{@code WikiUser}）连同用户组转成插件钩子看到的视图
 * （与 {@code WikiPageController#viewerOf} 一致，供移动 / 上传 / 删除 / 封禁这些动作接线点复用）。
 */
public final class Viewers {

    private Viewers() {
    }

    /** 当前用户视图（匿名 / 无效用户返回 {@link Viewer#ANONYMOUS}）。 */
    public static Viewer of(WikiUser user, PermissionService permissionService) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = permissionService == null ? Set.of() : permissionService.namesOf(user.getId());
        return new Viewer(user.getId(), user.getUsername(), false, groups,
                groups.contains(PermissionService.GROUP_BOT));
    }
}
