package cc.thonly.wikikernel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 国际化配置：界面语言由 {@link WikiLocaleResolver} 决定。
 * <p>
 * 文案在 {@code messages.properties}（默认简体中文）及对应语言的资源文件；
 * 模板用 {@code #{key}}，Java 侧注入 Spring 的 MessageSource。
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        return new WikiLocaleResolver();
    }
}
