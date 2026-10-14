package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.plugin.wikiseo.SeoFakes;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoParserFunction;
import cc.thonly.wikikernel.plugin.wikiseo.hook.SeoTag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数解析与校验：{{#seo}} 参数、<seo> 标签内容 / 属性、description 语义、hreflang 前缀。
 */
class SeoMetadataTest {

    private static final SeoValidator VALIDATOR = SeoValidator.forGenerators(List.of(
            new MetaTagGenerator(), new OpenGraphGenerator(), new TwitterGenerator(), new SchemaOrgGenerator()));

    private static final SeoValidator ALL_GENERATORS = SeoValidator.forGenerators(List.of(
            new MetaTagGenerator(), new OpenGraphGenerator(), new TwitterGenerator(),
            new SchemaOrgGenerator(), new CitationGenerator(), new DublinCoreGenerator()));

    // ==================== 参数解析 ====================

    @Test
    void parsePairsSplitsOnFirstEqualsAndTrims() {
        SeoFakes.FakeFunctionCall call = new SeoFakes.FakeFunctionCall(new SeoFakes.FakeRenderContext("页面"),
                List.of(
                        "title=  标题  ",
                        "description= 值里有 = 号 ",
                        "=没有键",
                        "没有等号",
                        "keywords=SEO, 元数据",
                        "empty=   "));

        Map<String, String> values = SeoMetadata.parsePairs(call.args(), call::expand).values();

        assertEquals("标题", values.get("title"));
        assertEquals("值里有 = 号", values.get("description"));
        assertEquals("SEO, 元数据", values.get("keywords"));
        // 没有 =、键为空、值为空的项都被忽略
        assertFalse(values.containsKey(""));
        assertFalse(values.containsKey("没有等号"));
        assertFalse(values.containsKey("empty"));
        assertEquals(3, values.size());
    }

    @Test
    void valuesAreExpandedStrippedAndEntityDecoded() {
        SeoFakes.FakeFunctionCall call = new SeoFakes.FakeFunctionCall(new SeoFakes.FakeRenderContext("页面"),
                List.of("title={{名称}}", "description=<b>粗体</b>&amp;实体&nbsp;尾"))
                .template("名称", "  示例站  ");

        Map<String, String> values = SeoMetadata.parsePairs(call.args(), call::expand).values();

        assertEquals("示例站", values.get("title"));
        assertEquals("粗体&实体\u00a0尾", values.get("description"));
    }

    @Test
    void entityDecodingSupportsNumericAndCommonNamedEntities() {
        assertEquals("A&B \"x\" ©", SeoMetadata.decodeEntities("A&amp;B &quot;x&quot; &copy;"));
        assertEquals("中", SeoMetadata.decodeEntities("&#20013;"));
        assertEquals("中", SeoMetadata.decodeEntities("&#x4E2D;"));
        // 未知实体保持原样
        assertEquals("&unknown;", SeoMetadata.decodeEntities("&unknown;"));
    }

    // ==================== 写入页面属性 ====================

    @Test
    void unknownParametersAreIgnoredAndValuesWritten() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        SeoFakes.FakeFunctionCall call = new SeoFakes.FakeFunctionCall(context,
                List.of("title=标题", "不认识的参数=xxx", "keywords=SEO"));

        new SeoParserFunction(VALIDATOR).invoke(call);

        assertEquals("标题", context.properties.get("title"));
        assertEquals("SEO", context.properties.get("keywords"));
        assertFalse(context.properties.containsKey("不认识的参数"));
    }

    @Test
    void manualDescriptionIsMarkedAndAutoDescriptionIsDropped() {
        SeoFakes.FakeRenderContext manual = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(VALIDATOR).invoke(new SeoFakes.FakeFunctionCall(manual,
                List.of("description=手工描述", "title=标题")));
        assertEquals("手工描述", manual.properties.get("description"));
        assertEquals("1", manual.properties.get(SeoHead.MANUAL_DESCRIPTION_PROPERTY));

        SeoFakes.FakeRenderContext auto = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(VALIDATOR).invoke(new SeoFakes.FakeFunctionCall(auto,
                List.of("description=auto")));
        assertFalse(auto.properties.containsKey("description"));
        assertFalse(auto.properties.containsKey(SeoHead.MANUAL_DESCRIPTION_PROPERTY));

        SeoFakes.FakeRenderContext extracts = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(VALIDATOR).invoke(new SeoFakes.FakeFunctionCall(extracts,
                List.of("description=TextExtracts")));
        assertFalse(extracts.properties.containsKey("description"));
    }

    @Test
    void laterCallOverridesEarlierAndKeepsOtherProperties() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        SeoParserFunction function = new SeoParserFunction(VALIDATOR);

        function.invoke(new SeoFakes.FakeFunctionCall(context, List.of("title=旧标题", "keywords=旧词")));
        function.invoke(new SeoFakes.FakeFunctionCall(context, List.of("title=新标题")));

        assertEquals("新标题", context.properties.get("title"));
        // 第二次没有给 keywords，保留第一次的值（对应 array_merge(existing, new)）
        assertEquals("旧词", context.properties.get("keywords"));
    }

    @Test
    void parserFunctionReturnsHtmlComment() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        String result = new SeoParserFunction(VALIDATOR)
                .invoke(new SeoFakes.FakeFunctionCall(context, List.of("title=标题")));
        assertEquals("<!-- WikiSEO -->", result);
    }

    // ==================== <seo> 标签 ====================

    @Test
    void tagBodyIsSplitByPipe() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        SeoFakes.FakeTagCall call = new SeoFakes.FakeTagCall(context,
                "\n|title=标签标题\n|description=标签描述\n|keywords=SEO, 标签\n");

        String result = new SeoTag(VALIDATOR).render(call);

        assertEquals("", result);
        assertEquals("标签标题", context.properties.get("title"));
        assertEquals("标签描述", context.properties.get("description"));
        assertEquals("1", context.properties.get(SeoHead.MANUAL_DESCRIPTION_PROPERTY));
        assertEquals("SEO, 标签", context.properties.get("keywords"));
    }

    @Test
    void tagAttributesWinOverBody() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        SeoFakes.FakeTagCall call = new SeoFakes.FakeTagCall(context, "|title=内容标题|title_mode=replace")
                .attribute("title", "属性标题")
                .attribute("title_mode", "append");

        new SeoTag(VALIDATOR).render(call);

        assertEquals("属性标题", context.properties.get("title"));
        assertEquals("append", context.properties.get("title_mode"));
    }

    @Test
    void tagWithoutAttributesWritesNothing() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        new SeoTag(VALIDATOR).render(new SeoFakes.FakeTagCall(context, ""));
        assertTrue(context.properties.isEmpty());
    }

    // ==================== 参数集合 ====================

    @Test
    void hreflangPrefixIsAccepted() {
        assertTrue(SeoValidator.isHreflang("hreflang_de-de"));
        assertTrue(SeoValidator.isHreflang("hreflang_zh-cn"));
        assertFalse(SeoValidator.isHreflang("hreflang_"));
        assertFalse(SeoValidator.isHreflang("hrefWRONGlang_de-de"));
        assertEquals("de-de", SeoValidator.hreflangCode("hreflang_de-de"));

        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(VALIDATOR).invoke(new SeoFakes.FakeFunctionCall(context,
                List.of("hreflang_de-de=https://example.de/页面", "hrefWRONGlang_de-de=https://bad")));
        assertEquals("https://example.de/页面", context.properties.get("hreflang_de-de"));
        assertFalse(context.properties.containsKey("hrefWRONGlang_de-de"));
    }

    @Test
    void citationParametersRequireCitationGenerator() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(VALIDATOR).invoke(new SeoFakes.FakeFunctionCall(context,
                List.of("citation_doi=10.1/xyz")));
        assertFalse(context.properties.containsKey("citation_doi"));

        SeoFakes.FakeRenderContext withCitation = new SeoFakes.FakeRenderContext("页面");
        new SeoParserFunction(ALL_GENERATORS).invoke(new SeoFakes.FakeFunctionCall(withCitation,
                List.of("citation_doi=10.1/xyz", "dc.identifier.wikidata=Q42")));
        assertEquals("10.1/xyz", withCitation.properties.get("citation_doi"));
        assertEquals("Q42", withCitation.properties.get("dc.identifier.wikidata"));
    }

    @Test
    void twitterParametersAreAlwaysAccepted() {
        SeoFakes.FakeRenderContext context = new SeoFakes.FakeRenderContext("页面");
        SeoValidator metaOnly = SeoValidator.forGenerators(List.of(new MetaTagGenerator()));
        new SeoParserFunction(metaOnly).invoke(new SeoFakes.FakeFunctionCall(context,
                List.of("twitter_card=summary", "twitter_site=@Page")));
        assertEquals("summary", context.properties.get("twitter_card"));
        assertEquals("@Page", context.properties.get("twitter_site"));
    }

    // ==================== 配置 ====================

    @Test
    void configUsesDefaultsAndKebabCaseKeys() {
        WikiSeoConfig defaults = WikiSeoConfig.from(new MapPluginConfig(Map.of()));
        assertEquals(List.of("OpenGraph", "Twitter", "SchemaOrg"), defaults.metadataGenerators());
        assertEquals("summary_large_image", defaults.twitterCardType());
        assertFalse(defaults.disableLogoFallbackImage());
        assertFalse(defaults.enableAutoDescription());
        assertTrue(defaults.noindexPageTitles().isEmpty());

        WikiSeoConfig configured = WikiSeoConfig.from(new MapPluginConfig(Map.of(
                "metadata-generators", "OpenGraph, Citation",
                "twitter-card-type", "summary",
                "twitter-site-handle", "@Example",
                "noindex-page-titles", "草稿, 沙盒",
                "enable-auto-description", "true",
                "default-image", "Cover.png",
                "google-site-verification-key", "g-key")));
        assertEquals(List.of("OpenGraph", "Citation"), configured.metadataGenerators());
        assertEquals("summary", configured.twitterCardType());
        assertEquals("@Example", configured.twitterSiteHandle());
        assertEquals(List.of("草稿", "沙盒"), configured.noindexPageTitles());
        assertTrue(configured.enableAutoDescription());
        assertEquals("Cover.png", configured.defaultImage());
        assertEquals("g-key", configured.googleSiteVerificationKey());
    }
}
