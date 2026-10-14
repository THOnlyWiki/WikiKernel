package cc.thonly.wikikernel.plugin.usermerge.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.usermerge.UserMergeSupport;
import cc.thonly.wikikernel.plugin.usermerge.service.UserMergeStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户合并 API（插件自己的 {@code @RestController}，core 动态挂载；迁移前在 core 的
 * {@code controller/UserMergeApiController}）。只有「核查员」组可以把源账号数据归入目标账号。
 * <p>
 * 路由与迁移前一致：{@code POST /api/user-merge}，参数 {@code oldUser}（源）/ {@code newUser}（目标）/
 * {@code deleteOld}（可选，{@code true} = 合并后删除源账号）；当前用户走 {@code common.service.CurrentUser}
 * 桥（插件不依赖 core 的 {@code UserSession}）。
 */
@RestController
@RequestMapping("/api/user-merge")
public class UserMergeApiController {

    private final UserMergeStore store;
    private final UserMergeSupport support;
    private final CurrentUser currentUser;

    public UserMergeApiController(UserMergeStore store, UserMergeSupport support, CurrentUser currentUser) {
        this.store = store;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 合并用户：{@code oldUser}（必填）、{@code newUser}（必填）、{@code deleteOld}（可选）。 */
    @PostMapping
    public AjaxResult<Void> merge(@RequestParam("oldUser") String oldUser,
                                  @RequestParam("newUser") String newUser,
                                  @RequestParam(value = "deleteOld", required = false) String deleteOld) {
        Viewer performer = currentUser.viewer();
        AjaxResult<Void> denied = checkPermission(performer);
        if (denied != null) {
            return denied;
        }
        if (Strings.isBlank(oldUser) || Strings.isBlank(newUser)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, support.message("userMerge.nameRequired"));
        }
        boolean delete = "true".equalsIgnoreCase(Strings.trimToEmpty(deleteOld));
        return mapResult(store.merge(performer, oldUser.trim(), newUser.trim(), delete),
                oldUser.trim(), newUser.trim());
    }

    private AjaxResult<Void> checkPermission(Viewer performer) {
        if (performer == null || !performer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, support.message("permissions.loginRequired"));
        }
        if (!store.canMerge(performer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("userMerge.forbidden"));
        }
        return null;
    }

    private AjaxResult<Void> mapResult(UserMergeStore.Result result, String source, String target) {
        return switch (result) {
            case OK -> AjaxResult.success(support.message("userMerge.done", null, source, target), null);
            case SOURCE_NOT_FOUND -> AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("userMerge.sourceNotFound"));
            case TARGET_NOT_FOUND -> AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("userMerge.targetNotFound"));
            case SAME_USER -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("userMerge.sameUser"));
        };
    }
}