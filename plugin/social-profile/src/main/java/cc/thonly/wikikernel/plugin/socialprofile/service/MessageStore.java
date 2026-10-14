package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;

import java.util.List;
import java.util.Optional;

/**
 * 私信数据访问（{@code wiki_message} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据：读写私信、标记已读、自己一侧的软删除；规则由 {@link MessageCenter} 负责，
 * 实现见 {@link MessageStoreJdbc}。
 */
public interface MessageStore {

    /** 插入私信（返回带自增 ID 的实体）。 */
    WikiMessage insert(WikiMessage message);

    /** 按 ID 查私信。 */
    Optional<WikiMessage> find(long messageId);

    /** 更新私信（已读标记、自己一侧的删除标记等）。 */
    void update(WikiMessage message);

    /** 物理删除（两侧都已删除时清理）。 */
    void delete(long messageId);

    /** 收件人是某人的私信（新的在前）。 */
    List<WikiMessage> received(long userId, int limit);

    /** 发件人是某人的私信（新的在前）。 */
    List<WikiMessage> sent(long userId, int limit);

    /** 某人参与的全部私信（收发两个方向，按时间升序；上限由调用方决定）。 */
    List<WikiMessage> all(long userId, int limit);

    /** 与某人的往来（两个方向，按时间升序）。 */
    List<WikiMessage> conversation(long userId, long otherUserId, int limit);

    /** 未读数量（收件人 = 某人、未读、且收件人一侧未删除）。 */
    long unreadCount(long userId);
}
