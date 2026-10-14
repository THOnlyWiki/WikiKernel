package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.templateresolver.TemplateResolution;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证插件模板解析器：能从插件类加载器读取 templates/ 下的模板。 */
class PluginTemplateResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTemplateFromPluginClassLoader() throws Exception {
        Path template = tempDir.resolve("templates/special/demo.html");
        Files.createDirectories(template.getParent());
        Files.writeString(template, "<p>plugin template</p>", StandardCharsets.UTF_8);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, null)) {
            PluginTemplateResolver resolver = new PluginTemplateResolver(() -> List.of(classLoader));

            TemplateResolution resolution = resolver.resolveTemplate(null, null, "special/demo", Map.of());

            assertNotNull(resolution);
            assertTrue(resolution.getTemplateResource().exists());
            try (var reader = resolution.getTemplateResource().reader()) {
                char[] buffer = new char[1024];
                int read = reader.read(buffer);
                String content = new String(buffer, 0, Math.max(read, 0));
                assertTrue(content.contains("plugin template"), content);
            }
        }
    }

    @Test
    void returnsNullWhenNoPluginHasTemplate() {
        PluginTemplateResolver resolver = new PluginTemplateResolver(List::of);

        assertNull(resolver.resolveTemplate(null, null, "special/missing", Map.of()));
    }
}
