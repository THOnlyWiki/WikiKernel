package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
/**
 * SocialProfile 的 API 公共基类（{@code @RestController} 的父类）：统一
 * 「功能关闭 403 / 未登录 401 / 被封禁 403」三个闸门、错误信封、管理员判断与留言行序列化。
 * <p>
 * 权限判断一律走 {@code common.service.AccessCheck} / {@code CurrentUser}（插件不自己写规则）。
 */
abstract class SocialProfileApiSupport {

    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final SocialProfileSupport support;
    private final UserStore users;

    protected SocialProfileApiSupport(AccessCheck access, CurrentUser currentUser,
                                      SocialProfileSupport support, UserStore users) {
        this.access = access;
        this.currentUser = currentUser;
        this.support = support;
        this.users = users;
    }

    // ==================== 闸门 ====================

    /** 功能开关关闭时的错误；开启时返回 null。 */
    protected Map<String, Object> gate() {
        if (!support.enabled()) {
            return error("feature.userInteractionDisabled");
        }
        return null;
    }

    /** 登录 + 封禁闸门（未登录 / 被封禁 / 功能关闭时返回错误信封）。 */
    protected Map<String, Object> gateLoggedIn() {
        Map<String, Object> closed = gate();
        if (closed != null) {
            return closed;
        }
        if (!loggedIn()) {
            return error("permissions.loginRequired");
        }
        return blockGate();
    }

    /** 只判封禁（登录判断交给服务层，用于允许匿名的入口）。 */
    protected Map<String, Object> blockGate() {
        String block = access == null ? null : access.blockNotice(viewer()).orElse(null);
        return block == null ? null : error(block);
    }

    // ==================== 当前用户 ====================

    /** 当前用户（匿名时 id = 0 / name = 空串）。 */
    protected CurrentUser currentUser() {
        return currentUser;
    }

    /** 当前用户 ID（匿名 0）。 */
    protected long userId() {
        return currentUser == null ? 0 : currentUser.id();
    }

    /** 是否登录。 */
    protected boolean loggedIn() {
        return currentUser != null && currentUser.loggedIn();
    }

    /** 当前请求用户（Viewer）。 */
    protected Viewer viewer() {
        return currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
    }

    /**
     * 当前用户是不是管理员：{@code 管理员} / {@code 行政员}（core 的组名）以及英文别名
     * {@code sysop} / {@code bureaucrat} 都算——组名集合来自 core 的 {@code CurrentUser}，
     * 插件不自己查权限表。
     */
    protected boolean isAdmin() {
        for (String group : ADMIN_GROUPS) {
            if (currentUser != null && currentUser.inGroup(group)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 信封 ====================

    /** 成功：{@code {success: true, message, data}}。 */
    protected Map<String, Object> ok(String messageKey, Object data, Object... args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message(messageKey, args));
        body.put("data", data);
        return body;
    }

    /** 失败（参数是消息键，漏翻时显示键本身）。 */
    protected Map<String, Object> error(String messageKey, Object... args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message(messageKey, args));
        body.put("data", null);
        return body;
    }

    /**
     * 把服务层抛出的异常转成错误信封：异常 message 是**消息键**
     * （{@code socialProfile.*} / {@code permissions.*} / {@code feature.*}）时原样本地化，
     * 其它异常统一按 {@code socialProfile.error.failed} 处理（不把内部细节透给前端）。
     */
    protected Map<String, Object> fail(RuntimeException exception) {
        String key = Strings.trimToEmpty(exception.getMessage());
        if (key.startsWith("socialProfile.") || key.startsWith("permissions.") || key.startsWith("feature.")) {
            return error(key);
        }
        return error("socialProfile.error.failed");
    }

    /** 本地化文案（键缺失时显示键本身，绝不回落中文硬编码）。 */
    protected String message(String key, Object... args) {
        return support.message(key, null, args);
    }

    // ==================== 参数 ====================

    /** 用户名 → 用户 ID（空白 / 找不到 = 0）。 */
    protected long userIdOf(String username) {
        String name = Strings.trimToEmpty(username);
        if (name.isEmpty() || users == null) {
            return 0;
        }
        return users.byName(name).map(ref -> ref.id()).orElse(0L);
    }

    /** 留言行 → 响应里的 Map（脚本刷新板面用；正文原样回传，前端负责转义）。 */
    protected static Map<String, Object> boardRow(WikiUserBoard row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("parentId", row.getParentId());
        view.put("toUserId", row.getToUserId());
        view.put("fromUserId", row.getFromUserId());
        view.put("deleted", Boolean.TRUE.equals(row.getDeleted()));
        view.put("content", row.getContent());
        view.put("createAt", row.getCreateAt() == null ? "" : row.getCreateAt().toString());
        return view;
    }

    /** 管理员组名（core 用中文组名，英文别名一并认）。 */
    private static final Set<String> ADMIN_GROUPS =
            Set.of("管理员", "行政员", "sysop", "bureaucrat");
}
