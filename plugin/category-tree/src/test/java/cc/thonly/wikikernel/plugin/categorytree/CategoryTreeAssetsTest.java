package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeHeadProvider;
import cc.thonly.wikikernel.plugin.categorytree.web.CategoryTreeAssetController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件资源（前端 JS / CSS、特殊页模板）必须能被类加载器读到：
 * {@code CategoryTreeAssetController} 按 {@code static/plugins/category-tree/…} 读取，
 * {@code CategoryTreeHeadProvider} 把同样的路径写进 &lt;head&gt;，
 * 特殊页模板按 {@code templates/special/category-tree.html} 解析。
 */
class CategoryTreeAssetsTest {

    /** 与 {@code CategoryTreeAssetController} 的 RESOURCE_PREFIX 保持一致。 */
    private static final String STATIC_PREFIX = "static/plugins/category-tree/";

    @Test
    void scriptIsPackagedAndKeepsAjaxContract() throws IOException {
        String script = read(STATIC_PREFIX + "ext.categoryTree.js");
        assertTrue(script.contains("/api.php?action=categorytree"), script);
        assertTrue(script.contains("'&category=' + encodeURIComponent( title )"), script);
        assertTrue(script.contains("'&options=' + encodeURIComponent( options )"), script);
        assertTrue(script.contains("&formatversion=2"), script);
        assertTrue(script.contains("data.categorytree"), script);
        assertTrue(script.contains(".CategoryTreeToggle"), script);
        assertTrue(script.contains(".CategoryTreeChildren"), script);
        assertTrue(script.contains("window.CategoryTreeMessages"), script);
        // 不使用 MediaWiki 专有 API（注释里提到 mw.* 不算：先去注释再检查）
        String code = script.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        for (String forbidden : new String[]{"mw.Api", "mw.msg(", "mw.hook(", "mw.config", "mw.loader", "mw.msg"}) {
            assertTrue(!code.contains(forbidden), "代码里不应出现 " + forbidden);
        }
    }

    @Test
    void stylesheetKeepsFrontendContract() throws IOException {
        String css = read(STATIC_PREFIX + "category-tree.css");
        assertTrue(css.contains(".CategoryTreeChildren"), css);
        assertTrue(css.contains("margin-left: 1.25em"), css);
        assertTrue(css.contains(".CategoryTreeToggle[aria-expanded='false']"), css);
        assertTrue(css.contains(".CategoryTreePageBullet"), css);
        assertTrue(css.contains(".CategoryTreeNotice"), css);
        assertTrue(css.contains(".CategoryTreeParents"), css);
    }

    @Test
    void specialPageTemplateIsPackaged() throws IOException {
        String template = read("templates/special/category-tree.html");
        assertTrue(template.contains("~{__${skin.layout}__ :: layout(${title}, ~{:: section})}"), template);
        assertTrue(template.contains("name=\"target\""), template);
        assertTrue(template.contains("name=\"namespaces\""), template);
        assertTrue(template.contains("name=\"showcount\""), template);
        assertTrue(template.contains("/plugins/category-tree/ext.categoryTree.js"), template);
        assertTrue(template.contains("/plugins/category-tree/category-tree.css"), template);
    }

    @Test
    void pluginDescriptorAndSpiArePackaged() throws IOException {
        String descriptor = read("META-INF/wiki-plugin.properties");
        assertTrue(descriptor.contains("plugin.id=category-tree"), descriptor);
        assertTrue(descriptor.contains(
                "plugin.mainClass=cc.thonly.wikikernel.plugin.categorytree.CategoryTreePlugin"), descriptor);
        String spi = read("META-INF/services/cc.thonly.wikikernel.common.plugin.WikiPlugin").trim();
        assertTrue(spi.contains("cc.thonly.wikikernel.plugin.categorytree.CategoryTreePlugin"), spi);
    }

    private static String read(String resource) throws IOException {
        ClassLoader classLoader = CategoryTreeAssetsTest.class.getClassLoader();
        assertNotNull(classLoader.getResource(resource), "缺少插件资源：" + resource);
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
