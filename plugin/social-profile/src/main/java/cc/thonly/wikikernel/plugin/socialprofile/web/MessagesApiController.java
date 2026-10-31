package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 私信 API（插件自己的 {@code @RestController}，由 core 动态挂载）：
 * 发送 {@code POST /api/messages/send}、删除自己一侧 {@code POST /api/messages/delete}。
 * <p>
 * 与迁移前 core 的 {@code MessageApiController} 语义一致：需要登录（未登录 401）、被封禁 403、
 * 功能关闭 403、收件人不存在 / 发给自己 400（消息键 {@code messages.userNotFound} /
 * {@code messages.selfNotAllowed}）、无关用户删除 404。
 */
@RestController
@RequestMapping("/api/messages")
public class MessagesApiController {

    private final MessageCenter messages;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final SocialProfileSupport support;
    private final UserStore users;

    public MessagesApiController(MessageCenter messages, AccessCheck access, CurrentUser currentUser,
                                 SocialProfileSupport support, UserStore users) {
        this.messages = messages;
        this.access = access;
        this.currentUser = currentUser;
        this.support = support;
        this.users = users;
    }

    // ==================== 读（App / 前端列表用） ====================

    /** 收件箱：最近若干条，App 自己按对方分组显示会话列表 */
    @GetMapping("/list")
    public AjaxResult<Map<String, Object>> list(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        int size = Math.max(1, Math.min(200, limit));
        // 用双向可见的消息：只查收件箱的话，自己发起的会话在对方回复前不会出现
        List<WikiMessage> all = messages.allVisible(viewer.id());
        int from = Math.max(0, all.size() - size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiMessage message : all.subList(from, all.size())) {
            rows.add(row(message, viewer.id()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", rows);
        data.put("unread", messages.unreadCount(viewer.id()));
        return AjaxResult.success(data);
    }

    /**
     * 按用户名搜人（App 私信页「找用户发起会话」用）。
     *
     * <p>只返回用户名 + 头像，够列表用；结果里去掉自己。
     */
    @GetMapping("/search-users")
    public AjaxResult<Map<String, Object>> searchUsers(
            @RequestParam(value = "q", required = false, defaultValue = "") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        String keyword = q == null ? "" : q.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!keyword.isEmpty()) {
            for (UserStore.UserRef user : users.search(keyword, Math.max(1, Math.min(50, limit)))) {
                if (user.id() == viewer.id()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", user.id());
                row.put("username", user.username());
                row.put("avatar", user.avatarUrl(UserStore.DEFAULT_AVATAR_URL));
                rows.add(row);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", rows);
        return AjaxResult.success(data);
    }

    /** 与某人的完整对话；打开即把对方发来的未读标为已读 */
    @GetMapping("/thread")
    public AjaxResult<Map<String, Object>> thread(@RequestParam("with") String with) {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        String name = Strings.trimToEmpty(with);
        if (name.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("messages.recipientRequired"));
        }
        Optional<UserStore.UserRef> other = users.byName(name);
        if (other.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, support.message("messages.notFound"));
        }
        long otherId = other.get().id();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiMessage message : messages.conversation(viewer.id(), otherId)) {
            rows.add(row(message, viewer.id()));
        }
        messages.markConversationRead(viewer.id(), otherId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", rows);
        data.put("with", other.get().username());
        return AjaxResult.success(data);
    }

    /** 未读数（抽屉角标用） */
    @GetMapping("/unread-count")
    public AjaxResult<Map<String, Object>> unreadCount() {
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", messages.unreadCount(viewer.id()));
        return AjaxResult.success(data);
    }

    private Map<String, Object> row(WikiMessage message, long meId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", message.getId());
        map.put("fromId", message.getFromUserId());
        map.put("toId", message.getToUserId());
        map.put("from", usernameOf(message.getFromUserId()));
        map.put("to", usernameOf(message.getToUserId()));
        map.put("avatar", avatarOf(message.getFromUserId()));
        map.put("content", message.getContent());
        map.put("read", Boolean.TRUE.equals(message.getRead()));
        map.put("incoming", message.getToUserId() != null && message.getToUserId() == meId);
        map.put("createAt", message.getCreateAt() == null ? "" : message.getCreateAt().toString());
        return map;
    }

    /** 头像地址（站内相对路径，默认头像由客户端自己画） */
    private String avatarOf(Long userId) {
        if (userId == null) {
            return "";
        }
        return users.byId(userId)
                .map(ref -> ref.avatarUrl(UserStore.DEFAULT_AVATAR_URL))
                .orElse("");
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "";
        }
        return users.byId(userId).map(UserStore.UserRef::username).orElse("");
    }

    /** 发送私信（wikitext 内容）。 */
    @PostMapping("/send")
    public AjaxResult<Map<String, Object>> send(@RequestParam("to") String to,
                                                @RequestParam("content") String content) {
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
        String recipient = Strings.trimToEmpty(to);
        if (recipient.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("messages.recipientRequired"));
        }
        String text = Strings.trimToEmpty(content);
        if (text.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("messages.empty"));
        }
        try {
            WikiMessage message = messages.send(viewer.id(), recipient, text);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", message.getId());
            return AjaxResult.success(support.message("messages.sent"), data);
        } catch (IllegalArgumentException exception) {
            String key = Strings.trimToEmpty(exception.getMessage());
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message(key.isEmpty() ? "messages.notFound" : key));
        }
    }

    /** 删除私信（自己一侧的软删除）。 */
    @PostMapping("/delete")
    public AjaxResult<Void> delete(@RequestParam("id") long id) {
        if (!support.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    support.message("feature.userInteractionDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        if (!messages.delete(viewer.id(), id)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("messages.notFound"));
        }
        return AjaxResult.success(support.message("messages.deleted"), null);
    }
}
