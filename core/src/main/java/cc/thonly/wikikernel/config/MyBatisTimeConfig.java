package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.db.type.LenientLocalDateTimeTypeHandler;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis 时间读取兼容：用 {@link LenientLocalDateTimeTypeHandler} 替换默认的
 * {@code LocalDateTimeTypeHandler}。
 * <p>
 * 只影响 Mapper 的 {@link LocalDateTime} 列。正常 DATETIME 列行为不变；SQLite / 旧库导入
 * MariaDB 后遗留的 TEXT / BLOB ISO 字符串也能读取。
 */
@Configuration(proxyBeanMethods = false)
public class MyBatisTimeConfig {

    @Bean
    public ConfigurationCustomizer wikiLocalDateTimeTypeHandler() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(LocalDateTime.class, new LenientLocalDateTimeTypeHandler());
    }
}
