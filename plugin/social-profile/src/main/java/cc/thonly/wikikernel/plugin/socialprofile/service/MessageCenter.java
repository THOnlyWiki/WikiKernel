package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 私信规则（插件侧，数据读写走 {@link MessageStore} 桥）：
 * <ul>
 *     <li>发送：收件人必须存在、不能发给自己（消息键 {@code messages.userNotFound} / {@code messages.selfNotAllowed}）；</li>
 *     <li>阅读：收件人打开后自动标已读（{@code readAt} 记时间）；</li>
 *     <li>删除：只删自己一侧（软删除），两侧都删则物理删除记录；</li>
 *     <li>会话：两个方向按时间拼接，打开会话把对方发来的未读标为已读。</li>
 * </ul>
 * 与迁移前 core 的 {@code MessageServiceImpl} 规则一致（未登录 / 封禁 / 开关判断在 API 与特殊页里）。
 */
@Component
public final class MessageCenter {

    /** 列表长度上限：0 = 不限制（与迁移前 core 的全量列表一致）。 */
    private static final int NO_LIMIT = 0;

    private final MessageStore messages;
    private final UserStore users;

    public MessageCenter(MessageStore messages, UserStore users) {
        this.messages = messages;
        this.users = users;
    }

    /** 收件箱（自己一侧未删除，新的在前）。 */
    public List<WikiMessage> inbox(long userId) {
        return messages.received(userId, NO_LIMIT);
    }

    /** 发件箱（自己一侧未删除，新的在前）。 */
    public List<WikiMessage> sent(long userId) {
        return messages.sent(userId, NO_LIMIT);
    }

    /** 自己可见的全部私信（收发两个方向、自己一侧未删除的），按时间升序（会话列表用）。 */
    public List<WikiMessage> allVisible(long userId) {
        return messages.all(userId, NO_LIMIT);
    }

    /** 与某人的往来（两个方向、自己一侧未删除的），按时间升序。 */
    public List<WikiMessage> conversation(long userId, long otherUserId) {
        return messages.conversation(userId, otherUserId, NO_LIMIT);
    }

    /** 未读数量（收件人是自己、未读、且自己一侧未删除）。 */
    public long unreadCount(long userId) {
        return userId <= 0 ? 0 : messages.unreadCount(userId);
    }

    /** 按 ID 查私信（不做权限判断）。 */
    public Optional<WikiMessage> find(long messageId) {
        return messages.find(messageId);
    }

    /**
     * 发送私信（内容按 wikitext 原样存储，展示时渲染）。
     *
     * @return 已写入的记录（带自增 ID）
     * @throws IllegalArgumentException 收件人不存在 / 发给自己（消息键）
     */
    public WikiMessage send(long fromUserId, String toUsername, String content) {
        String name = Strings.trimToEmpty(toUsername);
        UserStore.UserRef to = users.byName(name)
                .orElseThrow(() -> new IllegalArgumentException("messages.userNotFound"));
        if (fromUserId <= 0 || Objects.equals(fromUserId, to.id())) {
            throw new IllegalArgumentException("messages.selfNotAllowed");
        }
        WikiMessage message = new WikiMessage();
        message.setFromUserId(fromUserId);
        message.setToUserId(to.id());
        message.setContent(Strings.trimToEmpty(content));
        message.setRead(false);
        message.setFromDeleted(false);
        message.setToDeleted(false);
        message.setCreateAt(LocalDateTime.now());
        return messages.insert(message);
    }

    /**
     * 查看单条私信（必须是收发双方）；收件人阅读时自动标已读。
     *
     * @return 不可见 / 不存在时为空
     */
    public Optional<WikiMessage> view(long userId, long messageId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        WikiMessage message = messages.find(messageId).orElse(null);
        if (message == null) {
            return Optional.empty();
        }
        boolean recipient = Objects.equals(message.getToUserId(), userId)
                && !Boolean.TRUE.equals(message.getToDeleted());
        boolean sender = Objects.equals(message.getFromUserId(), userId)
                && !Boolean.TRUE.equals(message.getFromDeleted());
        if (!recipient && !sender) {
            return Optional.empty();
        }
        if (recipient && !Boolean.TRUE.equals(message.getRead())) {
            message.setRead(true);
            message.setReadAt(LocalDateTime.now());
            messages.update(message);
        }
        return Optional.of(message);
    }

    /** 把与某人的往来中「自己作为收件人」的未读消息标为已读（打开会话时调用）。 */
    public void markConversationRead(long userId, long otherUserId) {
        if (userId <= 0 || otherUserId <= 0) {
            return;
        }
        for (WikiMessage message : conversation(userId, otherUserId)) {
            boolean incoming = Objects.equals(message.getFromUserId(), otherUserId)
                    && Objects.equals(message.getToUserId(), userId);
            if (incoming && !Boolean.TRUE.equals(message.getRead())) {
                message.setRead(true);
                message.setReadAt(LocalDateTime.now());
                messages.update(message);
            }
        }
    }

    /** 软删除自己一侧（两侧都删则物理删除记录）；不是收发双方返回 false。 */
    public boolean delete(long userId, long messageId) {
        if (userId <= 0) {
            return false;
        }
        WikiMessage message = messages.find(messageId).orElse(null);
        if (message == null) {
            return false;
        }
        if (Objects.equals(message.getToUserId(), userId)) {
            message.setToDeleted(true);
        } else if (Objects.equals(message.getFromUserId(), userId)) {
            message.setFromDeleted(true);
        } else {
            return false;
        }
        if (Boolean.TRUE.equals(message.getFromDeleted()) && Boolean.TRUE.equals(message.getToDeleted())) {
            messages.delete(messageId);
        } else {
            messages.update(message);
        }
        return true;
    }

    /** 按用户名查用户（发私信时校验收件人 / 显示对方资料用）。 */
    public Optional<UserStore.UserRef> byName(String username) {
        return users.byName(username);
    }

    /** 批量取用户名（列表显示对方是谁）。 */
    public Map<Long, String> names(Collection<Long> userIds) {
        return users.names(userIds);
    }
}
