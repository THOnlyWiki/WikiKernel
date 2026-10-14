package cc.thonly.wikikernel.plugin.socialprofile.data;

import cc.thonly.wikikernel.common.data.DbColumn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户之间的社交关系（{@code wiki_user_relation}，仿 MediaWiki SocialProfile 的好友 / 仇敌）：
 * <ul>
 *     <li>{@link #TYPE_FRIEND}：好友（需要对方接受：{@link #STATUS_PENDING} → {@link #STATUS_ACCEPTED}）；</li>
 *     <li>{@link #TYPE_FOE}：仇敌（单向、立即生效，不需要对方同意）。</li>
 * </ul>
 * 一个方向一行：{@code fromUserId} 是发起人（加好友 / 加仇敌的人），{@code toUserId} 是对象。
 * 「好友」关系在好友列表里按**两个方向**都算（接受后插一行反向的 ACCEPTED 记录）。
 */
@Data
@TableName("wiki_user_relation")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserRelation {

    /** 好友关系。 */
    public static final String TYPE_FRIEND = "FRIEND";
    /** 仇敌关系。 */
    public static final String TYPE_FOE = "FOE";

    /** 等待对方接受（好友请求）。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已接受（好友）。 */
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 发起人（加好友 / 加仇敌的人）用户 ID。 */
    @DbColumn(nullable = false)
    private Long fromUserId;

    /** 对象（被加的人）用户 ID。 */
    @DbColumn(nullable = false)
    private Long toUserId;

    /** 关系类型：{@link #TYPE_FRIEND} / {@link #TYPE_FOE}。 */
    @DbColumn(nullable = false, length = 16, defaultValue = "FRIEND")
    private String relationType = TYPE_FRIEND;

    /** 好友请求状态：{@link #STATUS_PENDING} / {@link #STATUS_ACCEPTED}（仇敌恒为 ACCEPTED）。 */
    @DbColumn(nullable = false, length = 16, defaultValue = "PENDING")
    private String status = STATUS_PENDING;

    /** 建立时间（好友 = 请求发出时间）。 */
    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
