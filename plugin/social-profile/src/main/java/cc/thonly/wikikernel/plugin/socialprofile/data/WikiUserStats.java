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
 * 用户积分与等级（{@code wiki_user_stats}，仿 MediaWiki SocialProfile 的 {@code user_stats} / {@code user_level}）：
 * 每个用户一行，{@code points} 由插件的给分规则累加，{@code level} 由积分阈值表换算后**同步落库**
 * （排行榜不必自己再算一遍阈值；阈值表改了以后下一次给分时刷新）。
 */
@Data
@TableName("wiki_user_stats")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserStats {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 用户 ID（一个用户一行）。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 累计积分（>= 0）。 */
    @DbColumn(nullable = false, defaultValue = "0")
    private Long points = 0L;

    /** 等级（由积分阈值表换算，>= 1）。 */
    @DbColumn(nullable = false, defaultValue = "1")
    private Integer level = 1;

    /** 最近一次积分变动时间。 */
    @DbColumn
    private LocalDateTime updateAt;
}
