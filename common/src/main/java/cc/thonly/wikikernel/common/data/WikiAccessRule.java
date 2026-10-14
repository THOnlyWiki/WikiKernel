package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 访问控制规则（{@code wiki_access_rule}）：某个目标 + 动作允许哪些用户组。
 * <p>
 * 目标（{@link #target}）支持：
 * <ul>
 *     <li>{@code *}：全部页面（默认规则）；</li>
 *     <li>精确标题：如 {@code 首页}、{@code 特殊:日志}；</li>
 *     <li>前缀（以 {@code *} 结尾）：如 {@code 帮助:*}，匹配所有以「帮助:」开头的标题。</li>
 * </ul>
 * {@link #groupNames} 为 {@code |} 分隔的用户组（任一即可）；{@code *} 表示所有人（含匿名）；空串表示无人（禁止）。
 * 动作 id 见 {@code cc.thonly.wikikernel.common.access.AccessAction}。
 */
@Data
@TableName("wiki_access_rule")
@AllArgsConstructor
@NoArgsConstructor
public class WikiAccessRule {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 目标：{@code *} / 精确标题 / 前缀（以 * 结尾，如 帮助:*）。 */
    @DbColumn(nullable = false, length = 255)
    private String target;

    /** 动作 id：read / edit / create / move / protect / delete。 */
    @DbColumn(nullable = false, length = 32)
    private String action;

    /** 允许的用户组（| 分隔）；* = 所有人；空 = 无人。 */
    @DbColumn(nullable = false, length = 255)
    private String groupNames;
}
