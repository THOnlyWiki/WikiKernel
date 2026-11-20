package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.ai.*;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;

@Configuration(proxyBeanMethods = false)
public class AiRuntimeConfig {
    @Bean
    AiProviderRegistry aiProviderRegistry(AiConfig config, AiProviderFactory factory, MessageSource messages) {
        return new AiProviderRegistry(config, factory,
                (key, fallback) -> messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale()));
    }
}
