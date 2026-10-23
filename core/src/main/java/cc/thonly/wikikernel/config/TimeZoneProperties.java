package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务器时区配置（{@code LocalSettings.yaml} → {@code wikikernel.time}）：
 *
 * <pre>
 * wikikernel:
 *   time:
 *     zone: Asia/Shanghai
 * </pre>
 *
 * 启动时由 {@link TimeZoneConfig} 设置为 JVM 默认时区；留空 = 使用 JVM / 操作系统默认时区。
 * 评论等使用 {@code LocalDateTime.now()} 写入的服务器时间、以及没有单独设置时区的用户显示时间，
 * 都会跟随这里。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.time")
public class TimeZoneProperties {

    /** IANA 时区 ID（如 {@code Asia/Shanghai}）；空 = JVM / 操作系统默认。 */
    private String zone;
}
