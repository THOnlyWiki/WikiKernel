package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.time.WikiTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 把 {@code wikikernel.time.zone} 应用到 JVM 默认时区。
 * <p>
 * 这样 {@code LocalDateTime.now()} 和没有设置用户时区的显示都按站点配置的服务器时间工作；
 * 评论等插件写入的 {@code LocalDateTime} 也会使用同一个时区。
 * 留空 / 非法时保持 JVM 当前默认值（非法值会记录 warning，不会阻止启动）。
 */
@Configuration(proxyBeanMethods = false)
public class TimeZoneConfig {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneConfig.class);

    public TimeZoneConfig(TimeZoneProperties properties) {
        String configured = properties == null ? null : properties.getZone();
        String zoneId = WikiTime.normalizeZone(configured);
        if (zoneId.isEmpty()) {
            if (configured != null && !configured.isBlank()) {
                log.warn("wikikernel.time.zone 不是合法 IANA 时区：{}；继续使用 JVM 默认时区 {}",
                        configured, ZoneId.systemDefault());
            }
            return;
        }
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zoneId)));
        System.setProperty("user.timezone", zoneId);
        log.info("服务器时区已设置为 {}（影响 LocalDateTime.now / 评论时间 / 未设置用户时区的显示）", zoneId);
    }
}
