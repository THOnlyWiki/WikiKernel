package cc.thonly.wikikernel.config;

import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.AlwaysValidCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.StringTemplateResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 让插件 jar 里的模板也能被 Thymeleaf 解析：
 * 模板名 {@code special/hello} → 依次到插件类加载器读取 {@code templates/special/hello.html}。
 * <p>
 * core 自己的模板仍由 Boot 默认解析器处理（本解析器 order 更大，排在后面）。
 */
public class PluginTemplateResolver implements ITemplateResolver {

    private static final String PREFIX = "templates/";
    private static final String SUFFIX = ".html";

    private final Supplier<List<ClassLoader>> pluginClassLoaders;

    public PluginTemplateResolver(Supplier<List<ClassLoader>> pluginClassLoaders) {
        this.pluginClassLoaders = pluginClassLoaders;
    }

    @Override
    public String getName() {
        return "wikiPluginTemplateResolver";
    }

    @Override
    public Integer getOrder() {
        // Boot 默认模板解析器 order = 1，这里放后面：core 模板优先
        return 100;
    }

    @Override
    public TemplateResolution resolveTemplate(IEngineConfiguration configuration,
                                              String ownerTemplate,
                                              String template,
                                              Map<String, Object> templateResolutionAttributes) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String path = PREFIX + template + SUFFIX;
        for (ClassLoader classLoader : pluginClassLoaders.get()) {
            try (InputStream input = classLoader.getResourceAsStream(path)) {
                if (input == null) {
                    continue;
                }
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return new TemplateResolution(new StringTemplateResource(content),
                        TemplateMode.HTML, AlwaysValidCacheEntryValidity.INSTANCE);
            } catch (IOException ignored) {
                // 当前插件读取失败，尝试下一个
            }
        }
        return null;
    }
}
