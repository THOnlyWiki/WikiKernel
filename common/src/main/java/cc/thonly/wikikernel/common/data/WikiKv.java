package cc.thonly.wikikernel.common.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 插件键值表（{@code wiki_kv}）：给插件存自己的<b>小配置 / 小状态</b>用的通用 KV。
 * <p>
 * 抽象结构是 {@code (id, name, type, value)}：{@code name} 是唯一键，{@code value} 统一按长文本存，
 * {@code type} 说明怎么解释它（{@link #TYPE_STRING} / {@link #TYPE_NUMBER} / {@link #TYPE_JSON}）。
 * 面向插件的读写口是 {@code common.service.KvStore}（由 core 提供实现），插件不需要直接碰这张表。
 * <p>
 * 注意：值那一列的物理列名是 {@code kv_value} 而不是 {@code value} —— H2 2.x 把 {@code VALUE} 当保留字，
 * Auto-DDL 建表时 {@code value CLOB} 会直接 Syntax error（SQLite / MySQL 没这个问题，但 H2 是受支持的数据库）。
 * Java 字段仍叫 {@code value}，靠 {@code @TableField("kv_value")} 映射，抽象结构不受影响。
 */
@Data
@TableName("wiki_kv")
@AllArgsConstructor
@NoArgsConstructor
public class WikiKv {

    /** 值类型：普通字符串。 */
    public static final String TYPE_STRING = "string";
    /** 值类型：数值（文本形式存储，如 {@code 42}、{@code 3.5}）。 */
    public static final String TYPE_NUMBER = "number";
    /** 值类型：JSON（文本形式存储，读写走 Jackson 3 的 {@code JsonNode}）。 */
    public static final String TYPE_JSON = "json";

    /** 值列的物理列名（避开 H2 的保留字 {@code VALUE}）。 */
    private static final String COLUMN_VALUE = "kv_value";

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 键（唯一；不要写空 / 超过 191 字符）。 */
    @DbColumn(nullable = false, unique = true, length = 191, comment = "键（唯一）")
    private String name;

    /** 值类型（{@link #TYPE_STRING} / {@link #TYPE_NUMBER} / {@link #TYPE_JSON}）。 */
    @DbColumn(nullable = false, length = 16, defaultValue = "", comment = "值类型：string/number/json")
    private String type = TYPE_STRING;

    /** 值（统一按文本存；JSON / 数值都是它的文本形式）。物理列名 {@code kv_value}，见类注释。 */
    @TableField(COLUMN_VALUE)
    @DbColumn(type = DbType.LONGTEXT, comment = "值（文本形式；避开 H2 保留字 VALUE）")
    private String value = "";

    /** 最后写入时间（每次 put / increment 都刷新）。 */
    @DbColumn(nullable = false, comment = "最后写入时间")
    private LocalDateTime updateAt;
}
