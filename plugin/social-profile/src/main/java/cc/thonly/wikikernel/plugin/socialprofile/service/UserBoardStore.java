package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;

import java.util.List;
import java.util.Optional;

/**
 * 用户留言板数据访问（{@code wiki_user_board} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据：写 / 读 / 软删除留言。「谁能留言、能回复谁、谁可以删」这类规则由
 * {@link BoardService} 负责；实现见 {@link UserBoardStoreJdbc}（JDBC 直连 core 的
 * {@code common.service.PluginDatabase}）。
 */
public interface UserBoardStore {

    /** 插入留言（返回带自增 ID 的实体）。 */
    WikiUserBoard insert(WikiUserBoard message);

    /** 按 ID 查留言（含已软删除的，插件按 {@code deleted} 自己判断）。 */
    Optional<WikiUserBoard> find(long messageId);

    /** 更新留言（改正文 / 软删除标记）。 */
    void update(WikiUserBoard message);

    /** 物理删除（管理员彻底删除时用）。 */
    void delete(long messageId);

    /** 某个收件人（留言板主人）的留言，新的在前；{@code limit <= 0} = 不限制。 */
    List<WikiUserBoard> received(long toUserId, int limit);

    /** 某个收件人的留言总数（含已删除的，用于「共 N 条」计数）。 */
    long count(long toUserId);

    /** 某个发件人（留言者）写过的留言，新的在前。 */
    List<WikiUserBoard> bySender(long fromUserId, int limit);

    /** 某条留言的回复，按时间升序。 */
    List<WikiUserBoard> replies(long parentId, int limit);

    /** 最近的全站留言（新的在前；新留言播报 / 侧栏用）。 */
    List<WikiUserBoard> recent(int limit);
}
