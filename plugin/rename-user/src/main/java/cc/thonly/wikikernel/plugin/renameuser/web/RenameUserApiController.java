package cc.thonly.wikikernel.plugin.renameuser.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserSupport;
import cc.thonly.wikikernel.plugin.renameuser.service.UserAdminStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 重命名用户 API（插件自己的 {@code @RestController}，core 动态挂载；迁移前在 core 的
 * {@code controller/RenameUserApiController}）。只有「重命名用户」组的用户可以把账号改名。
 * <p>
 * 路由与迁移前一致：{@code POST /api/user/rename}，参数 {@code oldName} / {@code newName} /
 * {@code reason}（可空）/ {@code movePages}（可选，{@code 1} = 一并移动用户页）；当前用户走
 * {@code common.service.CurrentUser} 桥（插件不依赖 core 的 {@code UserSession}）。
 */
@RestController
@RequestMapping("/api/user")
public class RenameUserApiController {

    private final UserAdminStore store;
    private final RenameUserSupport support;
    private final CurrentUser currentUser;

    public RenameUserApiController(UserAdminStore store, RenameUserSupport support, CurrentUser currentUser) {
        this.store = store;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 重命名用户：{@code oldName}（必填）、{@code newName}（必填）、{@code reason}（可空）、{@code movePages}（可选）。 */
    @PostMapping("/rename")
    public AjaxResult<Map<String, Object>> rename(@RequestParam("oldName") String oldName,
                                                  @RequestParam("newName") String newName,
                                                  @RequestParam(value = "reason", required = false) String reason,
                                                  @RequestParam(value = "movePages", required = false) String movePages) {
        Viewer actor = currentUser.viewer();
        AjaxResult<Map<String, Object>> denied = checkPermission(actor);
        if (denied != null) {
            return denied;
        }
        if (Strings.isBlank(oldName) || Strings.isBlank(newName)) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.invalid"));
        }
        Optional<WikiUser> target = store.byName(oldName.trim());
        if (target.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.notFound", null, oldName.trim()));
        }
        boolean move = "1".equals(movePages) || "true".equalsIgnoreCase(Strings.trimToEmpty(movePages));
        return mapResult(store.rename(actor, target.get().getId(), oldName.trim(),
                newName.trim(), Strings.trimToEmpty(reason), move), oldName.trim(), newName.trim());
    }

    private AjaxResult<Map<String, Object>> checkPermission(Viewer actor) {
        if (actor == null || !actor.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, support.message("permissions.loginRequired"));
        }
        if (!store.canRename(actor)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("renameUser.noPermission"));
        }
        return null;
    }

    private AjaxResult<Map<String, Object>> mapResult(UserAdminStore.RenameResult result,
                                                      String oldName, String newName) {
        return switch (result) {
            case OK -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("url", RenameUserSupport.pagePath("用户:" + newName));
                data.put("moved", 0);
                yield AjaxResult.success(support.message("renameUser.success", null, oldName, newName), data);
            }
            case NO_SUCH_USER -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.notFound", null, oldName));
            case NAME_IN_USE -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.taken", null, newName));
            case SAME_NAME -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.same"));
            case INVALID_NAME -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("renameUser.invalid"));
        };
    }
}