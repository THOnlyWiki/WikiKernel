package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.plugin.PluginManager;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * 把 {@link PluginTemplateResolver} 挂到 Thymeleaf 引擎上，
 * 使插件 jar 的 {@code templates/} 目录可以直接写 Thymeleaf 模板。
 */
@Component
public class PluginTemplateSupport {

    private final SpringTemplateEngine templateEngine;
    private final PluginManager pluginManager;

    public PluginTemplateSupport(SpringTemplateEngine templateEngine, PluginManager pluginManager) {
        this.templateEngine = templateEngine;
        this.pluginManager = pluginManager;
    }

    @PostConstruct
    void registerPluginTemplateResolver() {
        templateEngine.addTemplateResolver(new PluginTemplateResolver(pluginManager::pluginClassLoaders));
    }
}
