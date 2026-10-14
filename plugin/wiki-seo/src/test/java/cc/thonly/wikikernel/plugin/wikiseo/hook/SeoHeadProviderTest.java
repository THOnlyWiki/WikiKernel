package cc.thonly.wikikernel.plugin.wikiseo.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.plugin.wikiseo.SeoFakes;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import cc.thonly.wikikernel.plugin.wikiseo.service.CitationGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.DublinCoreGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.MetaTagGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.OpenGraphGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SchemaOrgGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoHead;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoValidator;
import cc.thonly.wikikernel.plugin.wikiseo.service.TwitterGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Head 输出：<title> 覆盖、MetaTag（robots / 验证码 / hreflang / canonical）、
 * OpenGraph、Twitter、Schema.org、Citation、DublinCore 与自动描述。
 */
class SeoHeadProviderTest {

    private static final WikiSeoConfig DEFAULT_CONFIG = WikiSeoConfig.from(new MapPluginConfig(Map.of()));

    private static SeoHeadProvider provider(WikiSeoConfig config) {
        return provider(config, List.of(new MetaTagGenerator(), new OpenGraphGenerator(),
                new TwitterGenerator(), new SchemaOrgGenerator()), null);
    }

    private static SeoHeadProvider provider(WikiSeoConfig config, List<SeoGenerator> generators,
                                            SeoFakes.FakePageStore store) {
        return new SeoHeadProvider(config, SeoValidator.forGenerators(generators), generators, store);
    }

    private static String joined(List<String> items) {
        return String.join("\n", items);
    }

    private static String itemContaining(List<String> items, String needle) {
        return items.stream().filter(item -> item.contains(needle)).findFirst().orElse("");
    }

    // ==================== 默认输出 ====================

    @Test
    void defaultOutputContainsOpenGraphTwitterAndJsonLd() {
        SeoFakes.FakeHeadContext context = new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题")
                .property("description", "手工描述")
                .property("type", "article")
                .property("keywords", "SEO, 元数据");

        List<String> items = provider(DEFAULT_CONFIG).headItems(context);
        String html = joined(items);

        assertTrue(html.contains("<meta property=\"og:title\" content=\"示例页面\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:url\" content=\"http://localhost:8080/wiki/%E7%A4%BA%E4%BE%8B%E9%A1%B5%E9%9D%A2\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:site_name\" content=\"示例站\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:description\" content=\"手工描述\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:type\" content=\"article\"/>"), html);
        assertTrue(html.contains("<meta property=\"article:tag\" content=\"SEO\"/>"), html);
        assertTrue(html.contains("<meta property=\"article:tag\" content=\"元数据\"/>"), html);

        assertTrue(html.contains("<meta name=\"twitter:card\" content=\"summary_large_image\"/>"), html);
        assertTrue(html.contains("<meta name=\"twitter:title\" content=\"示例页面\"/>"), html);
        assertTrue(html.contains("<meta name=\"twitter:description\" content=\"手工描述\"/>"), html);

        assertTrue(html.contains("<script type=\"application/ld+json\">"), html);
        assertTrue(html.contains("\"@context\":\"https://schema.org\""), html);
        // 页面设置了 type=article，JSON-LD 与 og:type 都用它
        assertTrue(html.contains("\"@type\":\"article\""), html);
        assertTrue(html.contains("\"headline\":\"SEO 标题\""), html);
        assertTrue(html.contains("\"potentialAction\""), html);

        // description 同时进 <meta name="description">
        assertTrue(html.contains("<meta name=\"description\" content=\"手工描述\"/>"), html);
    }

    // ==================== robots / noindex ====================

    @Test
    void robotsParameterControlsPolicy() {
        List<String> indexed = provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("robots", "index,nofollow"));
        assertTrue(joined(indexed).contains("<meta name=\"robots\" content=\"index,nofollow\"/>"), joined(indexed));

        List<String> noindex = provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("robots", "noindex"));
        assertTrue(joined(noindex).contains("<meta name=\"robots\" content=\"noindex\"/>"), joined(noindex));

        // 两个策略之间允许空格
        List<String> spaced = provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("robots", " noindex , follow "));
        assertTrue(joined(spaced).contains("<meta name=\"robots\" content=\"noindex,follow\"/>"), joined(spaced));

        // 没有 robots 且没命中启发式：不输出 robots
        List<String> none = provider(DEFAULT_CONFIG).headItems(new SeoFakes.FakeHeadContext());
        assertFalse(joined(none).contains("name=\"robots\""), joined(none));
    }

    @Test
    void noindexHeuristicTriggersOnQueryParameters() {
        assertTrue(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("action", "edit"))).contains("content=\"noindex\""));
        assertTrue(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("diff", "123"))).contains("content=\"noindex\""));
        assertTrue(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("oldid", "42"))).contains("content=\"noindex\""));
        assertTrue(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("veaction", "editsource"))).contains("content=\"noindex\""));
        assertTrue(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("action", "history"))).contains("content=\"noindex\""));

        // action=view 之类的普通请求不 noindex
        assertFalse(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("action", "view"))).contains("name=\"robots\""));
        assertFalse(joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().query("action", "submit"))).contains("name=\"robots\""));
    }

    @Test
    void noindexHeuristicTriggersOnConfiguredTitles() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of(
                "noindex-page-titles", "首页, 草稿")));

        List<String> items = provider(config).headItems(new SeoFakes.FakeHeadContext().pageTitle("首页"));
        assertTrue(joined(items).contains("<meta name=\"robots\" content=\"noindex\"/>"), joined(items));

        // 带命名空间的页面按去掉命名空间后的标题匹配
        List<String> namespaced = provider(config).headItems(
                new SeoFakes.FakeHeadContext().pageTitle("项目:草稿"));
        assertTrue(joined(namespaced).contains("content=\"noindex\""), joined(namespaced));

        // title 请求参数命中清单
        List<String> queryTitle = provider(config).headItems(
                new SeoFakes.FakeHeadContext().pageTitle("普通页面").query("title", "草稿"));
        assertTrue(joined(queryTitle).contains("content=\"noindex\""), joined(queryTitle));

        // 页面属性里的 robots 优先于启发式
        List<String> explicit = provider(config).headItems(new SeoFakes.FakeHeadContext()
                .pageTitle("首页").property("robots", "index,follow"));
        assertTrue(joined(explicit).contains("content=\"index,follow\""), joined(explicit));
    }

    // ==================== 站点验证 / hreflang / canonical ====================

    @Test
    void verificationKeysAreRendered() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of(
                "google-site-verification-key", "g-key",
                "bing-site-verification-key", "b-key",
                "yandex-site-verification-key", "y-key",
                "pinterest-site-verification-key", "p-key",
                "norton-site-verification-key", "n-key",
                "naver-site-verification-key", "na-key",
                "baidu-site-verification-key", "ba-key",
                "facebook-app-id", "app-id",
                "facebook-admins", "1,2")));

        String html = joined(provider(config).headItems(new SeoFakes.FakeHeadContext()));

        assertTrue(html.contains("<meta name=\"google-site-verification\" content=\"g-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"msvalidate.01\" content=\"b-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"yandex-verification\" content=\"y-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"p:domain_verify\" content=\"p-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"norton-safeweb-site-verification\" content=\"n-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"naver-site-verification\" content=\"na-key\"/>"), html);
        assertTrue(html.contains("<meta name=\"baidu-site-verification\" content=\"ba-key\"/>"), html);
        // Facebook 用 property=（与 MediaWiki 版一致）
        assertTrue(html.contains("<meta property=\"fb:app_id\" content=\"app-id\"/>"), html);
        assertTrue(html.contains("<meta property=\"fb:admins\" content=\"1,2\"/>"), html);
    }

    @Test
    void hreflangDefaultLanguageAndPageParameters() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of("default-language", "zh-cn")));
        SeoFakes.FakeHeadContext context = new SeoFakes.FakeHeadContext()
                .property("hreflang_de-de", "https://example.de/页面")
                .property("hreflang_en-us", "https://example.com/page");

        String html = joined(provider(config).headItems(context));

        assertTrue(html.contains("<link rel=\"alternate\" href=\"http://localhost:8080/wiki/%E7%A4%BA%E4%BE%8B%E9%A1%B5%E9%9D%A2\" hreflang=\"zh-cn\"/>"), html);
        assertTrue(html.contains("<link rel=\"alternate\" href=\"https://example.de/页面\" hreflang=\"de-de\"/>"), html);
        assertTrue(html.contains("<link rel=\"alternate\" href=\"https://example.com/page\" hreflang=\"en-us\"/>"), html);
        // 输出顺序稳定：默认语言 → 页面参数（按语言码排序，core 的页面属性 Map 本身不保证顺序）
        assertTrue(html.indexOf("hreflang=\"zh-cn\"") < html.indexOf("hreflang=\"de-de\""), html);
        assertTrue(html.indexOf("hreflang=\"de-de\"") < html.indexOf("hreflang=\"en-us\""), html);
    }

    @Test
    void canonicalParameterOverridesLink() {
        String html = joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("canonical", "https://example.com/canonical")));
        assertTrue(html.contains("<link rel=\"canonical\" href=\"https://example.com/canonical\"/>"), html);
    }

    // ==================== 图片 ====================

    @Test
    void localImageFileResolvesUrlWidthAndHeight() {
        SeoFakes.FakeHeadContext context = new SeoFakes.FakeHeadContext()
                .property("image", "Example.png")
                .property("image_alt", "示例图")
                .file("Example.png", "http://localhost:8080/files/Example.png", 300, 200);

        String html = joined(provider(DEFAULT_CONFIG).headItems(context));

        assertTrue(html.contains("<meta property=\"og:image\" content=\"http://localhost:8080/files/Example.png\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:image:width\" content=\"300\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:image:height\" content=\"200\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:image:alt\" content=\"示例图\"/>"), html);
        assertTrue(html.contains("<meta name=\"twitter:image\" content=\"http://localhost:8080/files/Example.png\"/>"), html);
        assertTrue(html.contains("\"width\":300"), html);
    }

    @Test
    void defaultImageAndLogoFallback() {
        SeoFakes.FakeHeadContext withDefault = new SeoFakes.FakeHeadContext()
                .file("Cover.png", "/files/Cover.png", 100, 50);
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of("default-image", "Cover.png")));
        String html = joined(provider(config).headItems(withDefault));
        assertTrue(html.contains("<meta property=\"og:image\" content=\"http://localhost:8080/files/Cover.png\"/>"), html);

        // 没有 image / default-image 时退回站点 Logo
        String logo = joined(provider(DEFAULT_CONFIG).headItems(new SeoFakes.FakeHeadContext()
                .file("Logo.png", "http://localhost:8080/files/Logo.png", 160, 160)));
        assertTrue(logo.contains("<meta property=\"og:image\" content=\"http://localhost:8080/files/Logo.png\"/>"), logo);

        // 关闭 Logo 兜底后不输出图片
        WikiSeoConfig disabled = WikiSeoConfig.from(new MapPluginConfig(Map.of(
                "disable-logo-fallback-image", "true")));
        String none = joined(provider(disabled).headItems(new SeoFakes.FakeHeadContext()
                .file("Logo.png", "http://localhost:8080/files/Logo.png", 160, 160)));
        assertFalse(none.contains("og:image"), none);
    }

    @Test
    void absoluteImageUrlUsesGivenDimensions() {
        String html = joined(provider(DEFAULT_CONFIG).headItems(new SeoFakes.FakeHeadContext()
                .property("image", "https://cdn.example.com/a.jpg")
                .property("image_width", "1200")
                .property("image_height", "630")));

        assertTrue(html.contains("<meta property=\"og:image\" content=\"https://cdn.example.com/a.jpg\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:image:width\" content=\"1200\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:image:height\" content=\"630\"/>"), html);
    }

    @Test
    void relativeImagePathIsPrefixedWithBaseUrl() {
        String html = joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("image", "/uploads/a.png")));
        assertTrue(html.contains("content=\"http://localhost:8080/uploads/a.png\""), html);
    }

    // ==================== Twitter 优先级 ====================

    @Test
    void twitterSiteAndCardFollowWikiSeoPriority() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of(
                "twitter-site-handle", "@Global",
                "twitter-card-type", "summary")));

        String html = joined(provider(config).headItems(new SeoFakes.FakeHeadContext()
                .property("twitter_site", "@Page")
                .property("twitter_card", "player")));

        // 全局句柄优先；页面 twitter_card 覆盖配置
        assertTrue(html.contains("<meta name=\"twitter:site\" content=\"@Global\"/>"), html);
        assertFalse(html.contains("content=\"@Page\""), html);
        assertTrue(html.contains("<meta name=\"twitter:card\" content=\"player\"/>"), html);

        // 没有全局句柄时用页面参数
        String pageOnly = joined(provider(DEFAULT_CONFIG).headItems(
                new SeoFakes.FakeHeadContext().property("twitter_site", "@Page")));
        assertTrue(pageOnly.contains("<meta name=\"twitter:site\" content=\"@Page\"/>"), pageOnly);
        assertTrue(pageOnly.contains("<meta name=\"twitter:card\" content=\"summary_large_image\"/>"), pageOnly);
    }

    // ==================== <title> ====================

    @Test
    void titleModesReplaceAppendPrepend() {
        SeoHeadProvider provider = provider(DEFAULT_CONFIG);

        assertEquals("SEO 标题", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题")));
        assertEquals("SEO 标题", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "replace")));
        assertEquals("示例页面 - SEO 标题", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "append")));
        assertEquals("SEO 标题 - 示例页面", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "prepend")));
        // 参数值会被 trim（与原版 TagParser::expandWikiTextTagArray 一致），要保留空格得写 &nbsp;
        assertEquals("SEO 标题>>示例页面", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "prepend").property("title_separator", " >> ")));
        assertEquals("SEO 标题\u00a0>>\u00a0示例页面", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "prepend")
                .property("title_separator", "\u00a0>>\u00a0")));
        // 没有 title 参数：不覆盖 <title>
        assertNull(provider.htmlTitle(new SeoFakes.FakeHeadContext()));
        // 未知 title_mode 退回 replace
        assertEquals("SEO 标题", provider.htmlTitle(new SeoFakes.FakeHeadContext()
                .property("title", "SEO 标题").property("title_mode", "unknown")));
    }

    @Test
    void titleStripsNewlinesAndDecodesEntities() {
        SeoFakes.FakeHeadContext context = new SeoFakes.FakeHeadContext()
                .property("title", "第一行\n第二行 &amp; 结尾");
        assertEquals("第一行第二行 & 结尾", provider(DEFAULT_CONFIG).htmlTitle(context));
    }

    // ==================== 转义 ====================

    @Test
    void htmlAttributeValuesAreEscaped() {
        SeoFakes.FakeHeadContext context = new SeoFakes.FakeHeadContext()
                .property("image", "https://cdn.example.com/a.png")
                .property("description", "\"><script>alert(1)</script> & 危险")
                .property("image_alt", "\"引号\" & <尖括号>");

        String html = joined(provider(DEFAULT_CONFIG).headItems(context));

        assertFalse(html.contains("<script>alert(1)</script>"), html);
        assertTrue(html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt; &amp; 危险"), html);
        assertTrue(html.contains("content=\"&quot;引号&quot; &amp; &lt;尖括号&gt;\""), html);
    }

    // ==================== 自动描述 ====================

    @Test
    void autoDescriptionIsGeneratedFromPageContent() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of("enable-auto-description", "true")));
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore()
                .content(1, "示例页面", "{{信息框|标题=x}}\n'''示例页面'''是[[示例|一个示例]]。<ref>引用</ref>这里还有更多内容。",
                        LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        List<String> items = provider(config, List.of(new MetaTagGenerator(), new OpenGraphGenerator(),
                new TwitterGenerator(), new SchemaOrgGenerator()), store).headItems(new SeoFakes.FakeHeadContext());
        String html = joined(items);

        assertTrue(html.contains("<meta name=\"description\" content=\"示例页面是一个示例。这里还有更多内容。\"/>"), html);
        assertTrue(html.contains("<meta property=\"og:description\" content=\"示例页面是一个示例。这里还有更多内容。\"/>"), html);
        // 页面最后更新时间作为 article:modified_time / dateModified
        assertTrue(html.contains("<meta property=\"article:modified_time\" content=\"2026-01-02T03:04:05\"/>"), html);
        assertTrue(html.contains("\"dateModified\":\"2026-01-02T03:04:05\""), html);
    }

    @Test
    void manualDescriptionWinsOverAutoDescription() {
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of("enable-auto-description", "true")));
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore()
                .content(1, "示例页面", "自动生成的描述。", LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        String html = joined(provider(config, List.of(new MetaTagGenerator()), store).headItems(
                new SeoFakes.FakeHeadContext()
                        .property("description", "手工描述")
                        .property(SeoHead.MANUAL_DESCRIPTION_PROPERTY, "1")));

        assertTrue(html.contains("content=\"手工描述\""), html);
        assertFalse(html.contains("自动生成的描述"), html);
    }

    @Test
    void autoDescriptionDisabledByDefault() {
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore()
                .content(1, "示例页面", "自动生成的描述。", LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        String html = joined(provider(DEFAULT_CONFIG, List.of(new MetaTagGenerator()), store)
                .headItems(new SeoFakes.FakeHeadContext()));
        assertFalse(html.contains("description"), html);

        // description=auto 时也走自动描述
        WikiSeoConfig config = WikiSeoConfig.from(new MapPluginConfig(Map.of("enable-auto-description", "true")));
        String auto = joined(provider(config, List.of(new MetaTagGenerator()), store)
                .headItems(new SeoFakes.FakeHeadContext().property("description", "auto")));
        assertTrue(auto.contains("content=\"自动生成的描述。\""), auto);
    }

    // ==================== Citation / DublinCore ====================

    @Test
    void citationGeneratorEmitsAuthorPerSemicolon() {
        List<SeoGenerator> generators = List.of(new MetaTagGenerator(), new CitationGenerator());
        String html = joined(provider(DEFAULT_CONFIG, generators, null).headItems(new SeoFakes.FakeHeadContext()
                .property("citation_author", "张三; 李四")
                .property("citation_title", "论文标题")
                .property("citation_doi", "10.1000/xyz")
                .property("description", "不应输出 citation_ 前缀")));

        assertTrue(html.contains("<meta name=\"citation_author\" content=\"张三\"/>"), html);
        assertTrue(html.contains("<meta name=\"citation_author\" content=\"李四\"/>"), html);
        assertTrue(html.contains("<meta name=\"citation_title\" content=\"论文标题\"/>"), html);
        assertTrue(html.contains("<meta name=\"citation_doi\" content=\"10.1000/xyz\"/>"), html);
        assertFalse(html.contains("citation_description"), html);
    }

    @Test
    void dublinCoreGeneratorEmitsDcTags() {
        List<SeoGenerator> generators = List.of(new MetaTagGenerator(), new DublinCoreGenerator());
        String html = joined(provider(DEFAULT_CONFIG, generators, null).headItems(new SeoFakes.FakeHeadContext()
                .property("title", "标题")
                .property("description", "描述")
                .property("author", "作者")
                .property("locale", "zh_CN")
                .property("dc.identifier.wikidata", "Q42")));

        assertTrue(html.contains("<link rel=\"schema.DC\" href=\"http://purl.org/dc/clients/1.1/\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.title\" content=\"标题\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.description\" content=\"描述\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.creator\" content=\"作者\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.language\" content=\"zh_CN\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.publisher\" content=\"示例站\"/>"), html);
        assertTrue(html.contains("<meta name=\"DC.identifier\" content=\"http://localhost:8080/wiki/%E7%A4%BA%E4%BE%8B%E9%A1%B5%E9%9D%A2\"/>"), html);
        assertTrue(html.contains("<meta name=\"dc.identifier.wikidata\" content=\"Q42\"/>"), html);
    }

    // ==================== 生成器集合 ====================

    @Test
    void onlyConfiguredGeneratorsRun() {
        List<SeoGenerator> onlyMeta = List.of(new MetaTagGenerator());
        String html = joined(provider(DEFAULT_CONFIG, onlyMeta, null).headItems(
                new SeoFakes.FakeHeadContext().property("title", "标题").property("description", "描述")));

        assertTrue(html.contains("<meta name=\"description\" content=\"描述\"/>"), html);
        assertFalse(html.contains("og:"), html);
        assertFalse(html.contains("application/ld+json"), html);
        assertFalse(html.contains("twitter:"), html);
    }

    @Test
    void headContextPropertiesOnlyExposeKnownSeoParams() {
        Map<String, String> seo = SeoValidator.forGenerators(List.of(new MetaTagGenerator()))
                .seoProperties(Map.of("title", "标题", "notitle", "1", "hitcounter-views", "3"));
        assertEquals(Map.of("title", "标题"), seo);
    }

    @Test
    void updateTimeCanBeDisabledWithDash() {
        String html = joined(provider(DEFAULT_CONFIG).headItems(new SeoFakes.FakeHeadContext()
                .property("modified_time", "-")
                .property("published_time", "-")));
        assertFalse(html.contains("article:modified_time"), html);
        assertFalse(html.contains("article:published_time"), html);
    }

    @Test
    void publishedTimeFallsBackToModifiedTime() {
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore()
                .content(1, "示例页面", "正文", LocalDateTime.of(2026, 5, 6, 7, 8, 9));
        String html = joined(provider(DEFAULT_CONFIG, List.of(new MetaTagGenerator(), new OpenGraphGenerator()), store)
                .headItems(new SeoFakes.FakeHeadContext()));
        assertTrue(html.contains("<meta property=\"article:published_time\" content=\"2026-05-06T07:08:09\"/>"), html);
        assertTrue(html.contains("<meta property=\"article:modified_time\" content=\"2026-05-06T07:08:09\"/>"), html);
    }

    @Test
    void nonArticleTypeSkipsAutomaticTimes() {
        SeoFakes.FakePageStore store = new SeoFakes.FakePageStore()
                .content(1, "示例页面", "正文", LocalDateTime.of(2026, 5, 6, 7, 8, 9));
        String html = joined(provider(DEFAULT_CONFIG, List.of(new MetaTagGenerator(), new OpenGraphGenerator()), store)
                .headItems(new SeoFakes.FakeHeadContext().property("type", "website")));
        assertFalse(html.contains("article:modified_time"), html);

        // 显式给出 published_time 时仍然输出
        String explicit = joined(provider(DEFAULT_CONFIG, List.of(new MetaTagGenerator(), new OpenGraphGenerator()), store)
                .headItems(new SeoFakes.FakeHeadContext().property("type", "website")
                        .property("published_time", "2020-01-01")));
        assertTrue(explicit.contains("<meta property=\"article:published_time\" content=\"2020-01-01\"/>"), explicit);
    }

    @Test
    void headItemsNeverFailOnEmptyProperties() {
        HeadContext empty = new SeoFakes.FakeHeadContext();
        List<String> items = provider(DEFAULT_CONFIG).headItems(empty);
        assertFalse(items.isEmpty());
        assertFalse(joined(items).contains("content=\"\""), joined(items));
    }
}
