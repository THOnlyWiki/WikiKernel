package cc.thonly.wikikernel.transfer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间转换：数据库里存的是服务器时区的 {@link LocalDateTime}，
 * <ul>
 *     <li>MediaWiki 导出 XML 用 UTC（{@code 2026-10-07T03:25:50Z}）；</li>
 *     <li>WikiKernel XML / JSON 用带时区偏移的 ISO 时间（{@code 2026-10-07T11:25:50+08:00}），
 *     换服务器时区后也能还原正确时刻。</li>
 * </ul>
 */
public final class TransferTimes {

    private static final DateTimeFormatter UTC_SECONDS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private TransferTimes() {
    }

    /** 服务器时区 {@link LocalDateTime} → MediaWiki 的 UTC ISO 字符串。 */
    public static String toUtcIso(LocalDateTime time) {
        return UTC_SECONDS.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** MediaWiki 的 UTC ISO 字符串 → 服务器时区 {@link LocalDateTime}；解析失败返回 null。 */
    public static LocalDateTime fromUtcIso(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value.trim()), ZoneId.systemDefault());
        } catch (DateTimeParseException exception) {
            try {
                return fromOffsetIso(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** 服务器时区 {@link LocalDateTime} → 带偏移的 ISO 字符串。 */
    public static String toOffsetIso(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 带偏移（或纯本地）ISO 字符串 → 服务器时区 {@link LocalDateTime}；解析失败抛出。 */
    public static LocalDateTime fromOffsetIso(String value) {
        String text = value == null ? "" : value.trim();
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(text).toInstant(), ZoneId.systemDefault());
        } catch (DateTimeParseException exception) {
            return LocalDateTime.parse(text);
        }
    }
}
