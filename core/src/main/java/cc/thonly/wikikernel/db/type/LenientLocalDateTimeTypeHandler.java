package cc.thonly.wikikernel.db.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 兼容旧数据 / 导入数据的 {@link LocalDateTime} 类型处理器。
 * <p>
 * 正常的 MySQL / MariaDB 时间列是 {@code DATETIME}，JDBC 直接返回 {@link LocalDateTime}；
 * 但把 SQLite（TEXT）或其它导出数据导入 MariaDB 后，时间列可能仍是 {@code TEXT / BLOB}，
 * 值是 ISO-8601 字符串（例如 {@code 2026-10-17T11:47:11.958857200}）。
 * MariaDB JDBC 对 BLOB 调用 {@code getObject(..., LocalDateTime.class)} 会抛
 * {@code value ... (BLOB) cannot be decoded as ZoneDateTime}，导致启动种子和所有 Mapper 查询失败。
 * <p>
 * 这里先按普通类型读取，再兼容：
 * <ul>
 *     <li>{@code byte[] / Blob}：按 UTF-8 解码后解析；</li>
 *     <li>{@code String}：兼容空格 / {@code T} 分隔和最多 9 位小数；</li>
 *     <li>带 {@code Z} / 时区偏移的时间：转换到服务器本地时间；</li>
 *     <li>纯日期：按当天零点。</li>
 * </ul>
 * 这只是一个**读取兼容层**；正式部署仍建议把导入后的时间列迁移回 {@code DATETIME(3)}，
 * 否则时间比较 / 索引性能会受文本/BLOB 列影响。
 */
@MappedTypes(LocalDateTime.class)
public class LenientLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    private static final Logger log = LoggerFactory.getLogger(LenientLocalDateTimeTypeHandler.class);

    private static final DateTimeFormatter SQL_PARAMETER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final DateTimeFormatter[] LOCAL_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, LocalDateTime parameter,
                                    JdbcType jdbcType) throws SQLException {
        // 普通 DATETIME 与 MyBatis 默认行为一致；如果旧导入库把时间列留成了 BLOB，
        // 先以 TEXT 写入，否则 MariaDB 驱动可能把它当成二进制时间戳。
        try {
            int sqlType = statement.getParameterMetaData().getParameterType(index);
            if (sqlType == Types.BLOB || sqlType == Types.LONGVARBINARY || sqlType == Types.VARBINARY
                    || sqlType == Types.BINARY) {
                statement.setString(index, parameter.format(SQL_PARAMETER_FORMAT));
                return;
            }
        } catch (SQLException ignored) {
            // 部分驱动不提供参数元数据；退回默认绑定
        }
        statement.setObject(index, parameter);
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getObject(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getObject(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getObject(columnIndex));
    }

    /** 把 JDBC / 文本 / BLOB 值转成 LocalDateTime；无法识别时返回 null（不阻断整页查询）。 */
    static LocalDateTime parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        if (raw instanceof Timestamp value) {
            return value.toLocalDateTime();
        }
        if (raw instanceof java.sql.Date value) {
            return value.toLocalDate().atStartOfDay();
        }
        if (raw instanceof LocalDate value) {
            return value.atStartOfDay();
        }
        if (raw instanceof LocalTime value) {
            return LocalDate.now().atTime(value);
        }
        if (raw instanceof byte[] bytes) {
            return parseText(new String(bytes, StandardCharsets.UTF_8));
        }
        if (raw instanceof Blob blob) {
            try {
                return parseText(new String(blob.getBytes(1, (int) blob.length()), StandardCharsets.UTF_8));
            } catch (SQLException exception) {
                log.warn("读取 BLOB 时间列失败：{}", exception.getMessage());
                return null;
            }
        }
        if (raw instanceof CharSequence text) {
            return parseText(text.toString());
        }
        return parseText(String.valueOf(raw));
    }

    static LocalDateTime parseText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 去掉字符串两边可能残留的引号（某些导入工具会写入 "..."）
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        for (DateTimeFormatter formatter : LOCAL_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        // SQLite 常见的「空格分隔 + 任意位数小数」：规范成 ISO 的 T 后再解析。
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            // 继续尝试带时区的格式
        }
        // 带 Z / +08:00 的时间：按同一时刻换算到服务器本地时间
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            // 继续尝试
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
