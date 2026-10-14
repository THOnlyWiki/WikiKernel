package cc.thonly.wikikernel.common.service;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link PluginDatabase} 的一行查询结果：列名统一小写，方便 H2（大写）/ SQLite（原样）/ MySQL（原样）
 * 三种驱动取到同一个键。
 * <p>
 * 类型归一化（读写三库的差异都收在这里）：
 * <ul>
 *     <li>时间列：{@code TIMESTAMP}（MySQL / H2）与 ISO-8601 文本（SQLite）都能被
 *     {@link #dateTime(String)} / {@link #dateTimeText(String)} 读出；</li>
 *     <li>布尔列：{@code BOOLEAN}（H2）/ {@code TINYINT(1)}（MySQL）/ {@code INTEGER 0|1}（SQLite）
 *     都能被 {@link #boolOrNull(String)} 读出；</li>
 *     <li>数值列：{@link #longOrNull(String)} / {@link #intOrNull(String)} 兼容
 *     {@code Integer} / {@code Long} / {@code Double} / {@code BigDecimal} / 数字文本。</li>
 * </ul>
 * 不可变：{@code values} 会被复制成只读映射（列名统一小写）。
 */
public record DbRow(Map<String, Object> values) {

    public DbRow {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((column, value) -> {
                if (column != null) {
                    normalized.put(column.toLowerCase(Locale.ROOT), normalize(value));
                }
            });
        }
        values = Collections.unmodifiableMap(normalized);
    }

    /** 大文本 / 二进制列按值取出来（H2 的 CLOB / BLOB 是句柄对象，SQLite / MySQL 直接给 String / byte[]）。 */
    private static Object normalize(Object value) {
        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                return length <= 0 ? "" : clob.getSubString(1, (int) Math.min(length, Integer.MAX_VALUE));
            } catch (SQLException exception) {
                return "";
            }
        }
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int) Math.min(blob.length(), Integer.MAX_VALUE));
            } catch (SQLException exception) {
                return new byte[0];
            }
        }
        return value;
    }

    /** 该列是否存在（含值为 NULL 的列）。 */
    public boolean has(String column) {
        return values.containsKey(column.toLowerCase(Locale.ROOT));
    }

    /** 该列是否为 NULL（不存在的列也算 NULL）。 */
    public boolean isNull(String column) {
        return value(column) == null;
    }

    /** 原始值（不做任何转换；不存在 / NULL 都是 {@code null}）。 */
    public Object value(String column) {
        return values.get(column.toLowerCase(Locale.ROOT));
    }

    /** 文本值（NULL → 空串；其它类型 {@code String.valueOf}）。 */
    public String string(String column) {
        Object value = value(column);
        return value == null ? "" : String.valueOf(value);
    }

    /** 文本值：NULL / 空串时为 {@code null}。 */
    public String textOrNull(String column) {
        String text = string(column);
        return text.isEmpty() ? null : text;
    }

    /** 整数值（NULL / 解析不出时为 {@code null}）。 */
    public Long longOrNull(String column) {
        Object value = value(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1L : 0L;
        }
        try {
            return new BigDecimal(value.toString().trim()).longValue();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 整数值（NULL / 解析不出时用 {@code fallback}）。 */
    public long longValue(String column, long fallback) {
        Long value = longOrNull(column);
        return value == null ? fallback : value;
    }

    /** 整数（int）值（NULL / 解析不出时为 {@code null}）。 */
    public Integer intOrNull(String column) {
        Long value = longOrNull(column);
        return value == null ? null : value.intValue();
    }

    /** 整数（int）值（NULL / 解析不出时用 {@code fallback}）。 */
    public int intValue(String column, int fallback) {
        Integer value = intOrNull(column);
        return value == null ? fallback : value;
    }

    /** 布尔值（NULL / 无法判断时为 {@code null}）：兼容 BOOLEAN / 0|1 / true|yes|on。 */
    public Boolean boolOrNull(String column) {
        Object value = value(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return switch (text) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> null;
        };
    }

    /** 布尔值（NULL / 无法判断时用 {@code fallback}）。 */
    public boolean boolValue(String column, boolean fallback) {
        Boolean value = boolOrNull(column);
        return value == null ? fallback : value;
    }

    /** 小数（NULL / 解析不出时为 {@code null}）。 */
    public BigDecimal decimalOrNull(String column) {
        Object value = value(column);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 时间值（NULL / 解析不出时为 {@code null}）：兼容 {@code TIMESTAMP}（MySQL / H2）、
     * {@code LocalDateTime} 与 SQLite 的 ISO-8601 文本。
     */
    public LocalDateTime dateTime(String column) {
        Object value = value(column);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        return parseDateTime(value.toString());
    }

    /** 时间值：{@code null} 或 ISO-8601 文本（与 core 的 {@code LocalDateTime.toString()} 一致）。 */
    public String dateTimeText(String column) {
        LocalDateTime dateTime = dateTime(column);
        if (dateTime != null) {
            return dateTime.toString();
        }
        return isNull(column) ? "" : string(column);
    }

    private static LocalDateTime parseDateTime(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        String normalized = value.replace(' ', 'T');
        int dot = normalized.indexOf('.');
        String candidate = dot > 0 ? normalized.substring(0, Math.min(normalized.length(), dot + 4)) : normalized;
        try {
            return LocalDateTime.parse(candidate);
        } catch (RuntimeException ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException ignored) {
            // 最后尝试日期（零点）
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 纯时间（{@code TIME} 列；NULL 时返回 {@code null}）。 */
    public LocalTime time(String column) {
        Object value = value(column);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime time) {
            return time;
        }
        try {
            return LocalTime.parse(value.toString().trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
