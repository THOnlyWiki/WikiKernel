package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 头像上传 API（插件自己的 {@code @RestController}，由 core 动态挂载）：{@code POST /api/user/avatar}。
 * <p>
 * 与迁移前 core 的 {@code UserAvatarApiController} 语义一致：仅本人（登录后）可上传 / 替换自己的头像；
 * 功能关闭 403、未登录 401、被封禁 403、账号不存在 404、图片校验失败 / 过大 400（消息键本地化）。
 * {@code /api/**} 的 CSRF 例外仍由 core 的 Spring Security 配置提供，插件不需要额外处理。
 */
@RestController
@RequestMapping("/api/user/avatar")
public class AvatarApiController {

    private final AvatarSupport avatars;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final SocialProfileSupport support;
    private final UserStore users;

    public AvatarApiController(AvatarSupport avatars, AccessCheck access, CurrentUser currentUser,
                               SocialProfileSupport support, UserStore users) {
        this.avatars = avatars;
        this.access = access;
        this.currentUser = currentUser;
        this.support = support;
        this.users = users;
    }

    /** 上传 / 替换当前用户头像。 */
    /** 当前用户头像地址（App 抽屉头部用）；没设过头像返回空串 */
    @GetMapping
    public AjaxResult<Map<String, Object>> current() {
        if (!support.enabled()) { return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("feature.userInteractionDisabled")); }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        String avatar = users.byId(viewer.id())
                .map(ref -> ref.avatarUrl(users.defaultAvatarUrl()))
                .orElse("");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("avatar", avatar);
        return AjaxResult.success(data);
    }

    /** 本人可以移除自己的头像；移除他人头像需要对 RemoveAvatar 特殊页有管理权限。 */
    @PostMapping("/remove")
    public AjaxResult<Map<String, Object>> remove(@RequestParam(value = "user", required = false) String username) {
        if (!support.enabled()) { return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("feature.userInteractionDisabled")); }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) { return AjaxResult.error(AjaxCode.UNAUTHORIZED, support.message("permissions.loginRequired")); }
        Optional<String> block = access.blockNotice(viewer);
        if (block.isPresent()) { return AjaxResult.error(AjaxCode.FORBIDDEN, block.get()); }
        var target = Strings.isBlank(username) ? users.byId(viewer.id()) : users.byName(username);
        if (target.isEmpty()) { return AjaxResult.error(AjaxCode.NOT_FOUND, support.message("permissions.userNotFound")); }
        if (target.get().id() != viewer.id() && !access.can(cc.thonly.wikikernel.common.access.AccessAction.PROTECT,
                "特殊:删除头像", viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("permissions.editForbidden"));
        }
        try {
            return AjaxResult.success(support.message("avatar.removed"), Map.of("url", avatars.remove(target.get().id())));
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, support.message(exception.getMessage()));
        }
    }

    @PostMapping
    public AjaxResult<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (!support.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    support.message("feature.userInteractionDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        Optional<String> block = access.blockNotice(viewer);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, block.get());
        }
        if (!avatars.exists(viewer.id())) {
            return AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("permissions.userNotFound"));
        }
        try {
            String url = avatars.store(viewer.id(),
                    file == null ? "" : file.getOriginalFilename(),
                    file == null ? "" : file.getContentType(),
                    file == null ? new byte[0] : file.getBytes());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", url);
            return AjaxResult.success(support.message("avatar.saved"), data);
        } catch (IllegalArgumentException exception) {
            String key = Strings.trimToEmpty(exception.getMessage());
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message(key.isEmpty() ? "avatar.uploadFailed" : key));
        } catch (IOException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("avatar.uploadFailed"));
        }
    }
}
