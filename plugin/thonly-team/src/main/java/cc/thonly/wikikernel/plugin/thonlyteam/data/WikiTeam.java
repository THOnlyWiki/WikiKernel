package cc.thonly.wikikernel.plugin.thonlyteam.data;

import cc.thonly.wikikernel.common.data.DbColumn;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一支组队帖。挂在某一场活动下面（{@code eventYear + eventId}），
 * 活动本身在 wiki 页面里，这里冗余存一份名称，列表就不用回查页面。
 * <p>
 * {@code contact} 是 QQ / 群号，**只有已加入的成员才看得到**，
 * 列表和未加入时的详情都不返回它。
 */
@Data
@TableName("wiki_team")
public class WikiTeam {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    @DbColumn(nullable = false)
    private Integer eventYear;

    @DbColumn(nullable = false)
    private Integer eventId;

    @DbColumn(length = 255)
    private String eventName;

    /** 拼车 car / 拼房 room / 同行 company / 摊位 booth / 拍摄 shoot */
    @DbColumn(length = 32)
    private String type;

    @DbColumn(nullable = false, length = 120)
    private String title;

    @DbColumn(length = 1000)
    private String note;

    /** 计划总人数（含团长） */
    @DbColumn
    private Integer capacity;

    /** 联系方式：QQ 号或群号，加入后才可见 */
    @DbColumn(length = 255)
    private String contact;

    /** 加入方式：instant 立即进入 / approve 需要团长批准 */
    @DbColumn(length = 16)
    private String joinMode;

    @DbColumn
    private Long ownerId;

    @DbColumn(length = 255)
    private String ownerName;

    /** open 招募中 / closed 已关闭 / deleted 被解散 */
    @DbColumn(length = 16)
    private String status;

    /** 解散人（站长一键解散时是站长，团长自己解散时是团长） */
    @DbColumn
    private Long deletedBy;

    @DbColumn
    private LocalDateTime deletedAt;

    @DbColumn
    private LocalDateTime createAt;

    @DbColumn
    private LocalDateTime updateAt;
}
