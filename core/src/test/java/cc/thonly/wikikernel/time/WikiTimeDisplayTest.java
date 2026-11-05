package cc.thonly.wikikernel.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiTimeDisplayTest {
    private final TimeZone original = TimeZone.getDefault();
    private final WikiTimeDisplay display = new WikiTimeDisplay();

    @AfterEach void restore() {
        WikiTime.clearZone();
        TimeZone.setDefault(original);
    }

    @Test void utcServerDisplaysShanghaiTimeAndCorrectlyCrossesMidnight() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        WikiTime.setZone(ZoneId.of("Asia/Shanghai"));
        assertEquals("2026年11月04日 22:08", display.format(LocalDateTime.of(2026, 11, 4, 14, 8), "yyyy年MM月dd日 HH:mm"));
        assertEquals("2026-11-05 01:09", display.format(LocalDateTime.of(2026, 11, 4, 17, 9), "yyyy-MM-dd HH:mm"));
    }

    @Test void shanghaiServerDoesNotApplyTheOffsetTwiceAndMissingTimeIsEmpty() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        WikiTime.setZone(ZoneId.of("Asia/Shanghai"));
        assertEquals("2026-11-04 22:09", display.format(LocalDateTime.of(2026, 11, 4, 22, 9), "yyyy-MM-dd HH:mm"));
        assertEquals("", display.format(null, "yyyy-MM-dd HH:mm"));
    }

    @Test void requestZonesAreIndependentAndAnonymousFallsBackToServerZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        LocalDateTime time = LocalDateTime.of(2026, 11, 4, 14, 8);
        WikiTime.setZone(ZoneId.of("Asia/Tokyo"));
        assertEquals("23:08", display.format(time, "HH:mm"));
        WikiTime.setZone(ZoneId.of("Asia/Shanghai"));
        assertEquals("22:08", display.format(time, "HH:mm"));
        WikiTime.clearZone();
        assertEquals("14:08", display.format(time, "HH:mm"));
    }
}
