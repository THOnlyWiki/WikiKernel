package cc.thonly.wikikernel.db.type;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 旧 SQLite / 导入 MariaDB 的 ISO 时间字符串兼容读取。 */
class LenientLocalDateTimeTypeHandlerTest {

    @Test
    void parsesIsoTextWithNanoseconds() {
        assertEquals(LocalDateTime.of(2026, 10, 17, 11, 47, 11, 958_857_200),
                LenientLocalDateTimeTypeHandler.parseText("2026-10-17T11:47:11.958857200"));
    }

    @Test
    void parsesSpaceSeparatedAndMillis() {
        assertEquals(LocalDateTime.of(2026, 10, 17, 11, 47, 11),
                LenientLocalDateTimeTypeHandler.parseText("2026-10-17 11:47:11"));
        assertEquals(LocalDateTime.of(2026, 10, 17, 11, 47, 11, 123_000_000),
                LenientLocalDateTimeTypeHandler.parseText("2026-10-17T11:47:11.123"));
    }

    @Test
    void parsesUtf8BlobBytes() {
        byte[] bytes = "2026-10-17T11:47:11.958857200".getBytes(StandardCharsets.UTF_8);
        assertEquals(LocalDateTime.of(2026, 10, 17, 11, 47, 11, 958_857_200),
                LenientLocalDateTimeTypeHandler.parse(bytes));
    }

    @Test
    void parsesTimestampAndRejectsEmpty() {
        Timestamp timestamp = Timestamp.valueOf("2026-10-17 11:47:11");
        assertEquals(timestamp.toLocalDateTime(), LenientLocalDateTimeTypeHandler.parse(timestamp));
        assertNull(LenientLocalDateTimeTypeHandler.parseText(""));
        assertNull(LenientLocalDateTimeTypeHandler.parseText("not-a-time"));
    }
}
