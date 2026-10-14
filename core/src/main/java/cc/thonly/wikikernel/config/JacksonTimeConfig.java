package cc.thonly.wikikernel.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 时间格式：{@link LocalDateTime} 序列化/反序列化统一使用 {@code yyyy-MM-dd HH:mm:ss}。
 * <p>
 * 只影响 REST 接口的 JSON 表现，不影响数据库列类型与读写
 * （Auto-DDL 仍按方言生成 MySQL DATETIME / H2 TIMESTAMP / SQLite TEXT）。
 * <p>
 * 想给单个字段用别的格式，可以在实体字段上加 {@code @JsonFormat} 覆盖
 * （实体在 common，需要在 common 引入 Jackson 注解依赖）。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonTimeConfig {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer wikiLocalDateTimeFormat() {
        return builder -> {
            SimpleModule module = new SimpleModule("wiki-time-format");
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            builder.addModule(module);
        };
    }
}
