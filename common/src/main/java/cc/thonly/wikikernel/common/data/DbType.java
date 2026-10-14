package cc.thonly.wikikernel.common.data;

/**
 * Auto-DDL 支持的列类型（跨库逻辑类型）。
 * <p>
 * 部分类型会按当前数据库翻译：
 * <ul>
 *     <li>{@link #LONGTEXT}：MySQL LONGTEXT / H2 CLOB / SQLite TEXT</li>
 *     <li>{@link #BOOLEAN}：MySQL TINYINT(1) / H2 BOOLEAN / SQLite INTEGER</li>
 *     <li>{@link #DATE} / {@link #TIME} / {@link #DATETIME}：MySQL/H2 原生时间类型，SQLite TEXT</li>
 *     <li>{@link #BLOB}：MySQL LONGBLOB，其余 BLOB</li>
 * </ul>
 * 需要支持新类型时：在这里加一个枚举值，再到 Auto-DDL 的 sqlType 里补一行映射即可。
 */
public enum DbType {

    /** 按 Java 字段类型自动推断（默认）。 */
    AUTO,

    /** 变长字符串，长度取 {@code @DbColumn(length = ...)}。 */
    VARCHAR,

    /** 普通长文本（MySQL 上限 64KB）。 */
    TEXT,

    /** 超长文本：MySQL LONGTEXT / H2 CLOB / SQLite TEXT。 */
    LONGTEXT,

    TINYINT,

    SMALLINT,

    INT,

    BIGINT,

    /** 布尔：MySQL TINYINT(1) / H2 BOOLEAN / SQLite INTEGER。 */
    BOOLEAN,

    FLOAT,

    DOUBLE,

    /** 定点数，精度取 {@code @DbColumn(precision = ..., scale = ...)}，默认 DECIMAL(19,4)。 */
    DECIMAL,

    /** 日期（不含时间）。 */
    DATE,

    /** 时间（不含日期）。 */
    TIME,

    /** 日期时间：MySQL DATETIME(3) / H2 TIMESTAMP / SQLite TEXT（ISO-8601）。 */
    DATETIME,

    /** 二进制：MySQL LONGBLOB，其余 BLOB。 */
    BLOB
}
