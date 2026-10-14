package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配：注册扩展点、生成器集合、安装向导。
 */
class WikiSeoPluginTest {

    private static SimplePluginContext start(Map<String, String> settings) {
        SimplePluginContext context = new SimplePluginContext("wiki-seo",
                Path.of("build", "test-working-dir"), new MapPluginConfig(settings));
        new WikiSeoPlugin().onStart(context);
        return context;
    }

    @Test
    void pluginRegistersExtensionsAndMetadata() {
        SimplePluginContext context = start(Map.of());

        assertEquals("wiki-seo", new WikiSeoPlugin().descriptor().id());
        assertEquals(1, context.lookup(ParserFunction.class).size());
        assertEquals("seo", context.lookup(ParserFunction.class).get(0).name());
        assertEquals(1, context.lookup(ParserTag.class).size());
        assertEquals("seo", context.lookup(ParserTag.class).get(0).name());
        assertEquals(1, context.lookup(HtmlHeadProvider.class).size());
        assertEquals(1, context.lookup(SetupStep.class).size());
        assertEquals("wiki-seo", context.lookup(SetupStep.class).get(0).id());
    }

    @Test
    void defaultGeneratorsAreMetaTagOpenGraphTwitterSchemaOrg() {
        SimplePluginContext context = start(Map.of());
        HtmlHeadProvider provider = context.lookup(HtmlHeadProvider.class).get(0);

        String html = String.join("\n", provider.headItems(new SeoFakes.FakeHeadContext()
                .property("title", "标题")
                .property("description", "描述")));

        assertTrue(html.contains("og:title"), html);
        assertTrue(html.contains("twitter:card"), html);
        assertTrue(html.contains("application/ld+json"), html);
        // 默认不启用 Citation / DublinCore
        assertFalse(html.contains("citation_"), html);
        assertFalse(html.contains("DC.title"), html);
    }

    @Test
    void configuredGeneratorsAreEnabledAndUnknownOnesIgnored() {
        SimplePluginContext context = start(Map.of(
                "metadata-generators", "Citation,DublinCore,OpenGraph,Unknown,Metatag"));
        HtmlHeadProvider provider = context.lookup(HtmlHeadProvider.class).get(0);

        String html = String.join("\n", provider.headItems(new SeoFakes.FakeHeadContext()
                .property("citation_title", "论文")
                .property("dc.identifier.wikidata", "Q42")));

        assertTrue(html.contains("citation_title"), html);
        assertTrue(html.contains("dc.identifier.wikidata"), html);
        assertTrue(html.contains("og:title"), html);
        // 未知生成器忽略；MetaTag 不重复注册
        assertFalse(html.contains("twitter:"), html);
    }

    @Test
    void setupStepWritesKebabCaseSettings() {
        SimplePluginContext context = start(Map.of());
        SetupStep setup = context.lookup(SetupStep.class).get(0);
        assertNotNull(setup);
        assertTrue(setup.fields().size() >= 19, "字段数量：" + setup.fields().size());

        SeoFakes.FakeSetupContext setupContext = new SeoFakes.FakeSetupContext();
        setup.apply(Map.of(
                "metadata-generators", "OpenGraph, Citation",
                "enable-auto-description", "true",
                "disable-logo-fallback-image", "true",
                "overwrite-page-image", "false",
                "default-image", "Cover.png"), setupContext);

        assertEquals("OpenGraph, Citation", setupContext.settings.get("metadata-generators"));
        assertEquals("true", setupContext.settings.get("enable-auto-description"));
        assertEquals("true", setupContext.settings.get("disable-logo-fallback-image"));
        assertEquals("false", setupContext.settings.get("overwrite-page-image"));
        assertEquals("Cover.png", setupContext.settings.get("default-image"));
        // 未填写的字段写空串而不是 null
        assertEquals("", setupContext.settings.get("default-language"));
    }

    @Test
    void pluginServicesAreRegisteredForAllExtensionPoints() {
        SimplePluginContext context = start(Map.of());
        List<SetupStep> steps = context.lookup(SetupStep.class);
        assertEquals(130, steps.get(0).order());
        assertEquals("WikiSEO", steps.get(0).displayName());
    }
}
