package cc.thonly.wikikernel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把配置全集目录（{@link ConfigCatalog}）注册成 Spring Bean。
 * <p>
 * 目录内容是只读常量（来自 classpath 资源 {@code config-catalog.yaml}），
 * 所以这里直接暴露单例，不重复解析。
 */
@Configuration
public class ConfigCatalogConfig {

    /** 配置全集目录（安装向导据此生成「全字段 + 默认值 + 中文注释」的配置文件）。 */
    @Bean
    public ConfigCatalog configCatalog() {
        return ConfigCatalog.get();
    }
}
