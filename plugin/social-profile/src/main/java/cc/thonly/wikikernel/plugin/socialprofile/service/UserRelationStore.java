package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserRelation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 好友 / 仇敌关系数据访问（{@code wiki_user_relation} 表——**表与数据访问都属于插件自己**）。
 * <p>
 * 只管数据：一个方向一行。「好友要先请求、仇敌单向生效、接受时补反向行」这类规则由
 * {@link FriendsService} 负责；实现见 {@link UserRelationStoreJdbc}。
 */
public interface UserRelationStore {

    /** 插入关系（返回带自增 ID 的实体）。 */
    WikiUserRelation insert(WikiUserRelation relation);

    /** 按 ID 查关系。 */
    Optional<WikiUserRelation> find(long relationId);

    /** 更新关系（主要是改状态 PENDING → ACCEPTED）。 */
    void update(WikiUserRelation relation);

    /** 物理删除（取消好友 / 删除仇敌 / 拒绝请求）。 */
    void delete(long relationId);

    /** 某个方向的关系（{@code from} 对 {@code to}，指定类型；没有返回空）。 */
    Optional<WikiUserRelation> find(long fromUserId, long toUserId, String relationType);

    /** 某人的好友 / 仇敌列表（自己发起的方向），新的在前。 */
    List<WikiUserRelation> outgoing(long fromUserId, String relationType, int limit);

    /** 别人加某人的记录（收到的待处理好友请求 / 别人把某人列为仇敌），新的在前。 */
    List<WikiUserRelation> incoming(long toUserId, String relationType, int limit);

    /** 某人待处理的好友请求数量（别人发给他的 PENDING）。 */
    long pendingFriendCount(long toUserId);

    /**
     * 一组用户的「好友数」（数 {@code fromUserId → *} 方向的 ACCEPTED 行；
     * 插件在接受好友时补一行反向记录，所以这个数就是好友数）。
     */
    Map<Long, Long> friendCounts(Collection<Long> userIds);
}
