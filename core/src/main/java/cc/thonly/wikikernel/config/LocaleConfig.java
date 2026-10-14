package cc.thonly.wikikernel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 国际化配置：语言由 {@link WikiLocaleResolver} 决定（{@code ?uselang=zh|en}）。
 * <p>
 * 文案在 {@code messages.properties}（默认中文）与 {@code messages_en.properties}；
 * 模板用 {@code #{key}}，Java 侧注入 Spring 的 MessageSource。
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        return new WikiLocaleResolver();
    }
}
