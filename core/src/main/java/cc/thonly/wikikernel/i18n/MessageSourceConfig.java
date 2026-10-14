package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息源装配：用 {@link PluginMessageSource} 替代 Boot 默认的 {@code messageSource}
 * （core 的 {@code messages*.properties} 优先，找不到时查插件 {@code MessageBundle}；
 * 两边都没有时返回**翻译键本身**，不回落中文）。
 */
@Configuration
public class MessageSourceConfig {

    @Bean
    public PluginMessageSource messageSource(ObjectProvider<PluginManager> pluginManagerProvider) {
        return new PluginMessageSource(pluginManagerProvider);
    }
}
