package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 服务器时区配置的回归测试：确保 {@code wikikernel.time.zone} 真的会落到 JVM 默认时区，
 * 这样评论里的 {@code LocalDateTime.now()} 才会写入配置的服务器时间。
 */
class TimeZoneConfigTest {

    @Test
    void appliesConfiguredZoneToJvmDefault() {
        TimeZone previousZone = TimeZone.getDefault();
        String previousProperty = System.getProperty("user.timezone");
        try {
            TimeZoneProperties properties = new TimeZoneProperties();
            properties.setZone("Asia/Shanghai");

            new TimeZoneConfig(properties);

            assertEquals(ZoneId.of("Asia/Shanghai"), ZoneId.systemDefault());
        } finally {
            TimeZone.setDefault(previousZone);
            if (previousProperty == null) {
                System.clearProperty("user.timezone");
            } else {
                System.setProperty("user.timezone", previousProperty);
            }
        }
    }

    @Test
    void invalidZoneKeepsJvmDefault() {
        TimeZone previousZone = TimeZone.getDefault();
        String previousProperty = System.getProperty("user.timezone");
        try {
            TimeZoneProperties properties = new TimeZoneProperties();
            properties.setZone("Not/AZone");

            new TimeZoneConfig(properties);

            assertEquals(previousZone, TimeZone.getDefault());
        } finally {
            TimeZone.setDefault(previousZone);
            if (previousProperty == null) {
                System.clearProperty("user.timezone");
            } else {
                System.setProperty("user.timezone", previousProperty);
            }
        }
    }
}
