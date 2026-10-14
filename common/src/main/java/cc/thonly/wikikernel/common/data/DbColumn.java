package cc.thonly.wikikernel.common.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Auto-DDL 列信息（只在建表 / 补字段时使用，不影响 MyBatis-Plus 自身的映射）。
 * <p>
 * 不标注时按 Java 类型推断：String → VARCHAR(255)、Long → BIGINT、
 * LocalDateTime → 各库时间类型（MySQL DATETIME / H2 TIMESTAMP / SQLite TEXT）、
 * Boolean → TINYINT(1)/BOOLEAN、BigDecimal → DECIMAL(19,4)……
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DbColumn {

    /** 显式列类型，见 {@link DbType}；默认 {@link DbType#AUTO} 按 Java 类型推断。 */
    DbType type() default DbType.AUTO;

    /** VARCHAR 长度，默认 255；AUTO 推断下大于 4000 时使用 TEXT。 */
    int length() default 255;

    /** DECIMAL 精度（仅 {@link DbType#DECIMAL} 生效）。 */
    int precision() default 19;

    /** DECIMAL 小数位（仅 {@link DbType#DECIMAL} 生效）。 */
    int scale() default 4;

    /** 是否允许 NULL，默认允许。 */
    boolean nullable() default true;

    /** 默认值（原样拼进 DDL，如 0、FALSE、CURRENT_TIMESTAMP）。 */
    String defaultValue() default "";

    /** 是否唯一（仅新建表时生效；已有表不会自动补唯一索引）。 */
    boolean unique() default false;

    /** 列注释（仅记录日志，避免不同数据库 COMMENT 语法差异）。 */
    String comment() default "";
}
