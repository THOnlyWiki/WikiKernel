package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.plugin.PluginConfig;

import java.util.List;

/**
 * WikiSEO 插件配置（对应 MediaWiki WikiSEO 的 {@code $wgMetadataGenerators} /
 * {@code $wgTwitterSiteHandle} / {@code $wgGoogleSiteVerificationKey} 等），在 {@code LocalSettings.yaml} 里：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       wiki-seo:
 *         metadata-generators: "OpenGraph,Twitter,SchemaOrg"
 *         twitter-site-handle: "@YourSite"
 *         twitter-card-type: summary_large_image
 *         default-image: Example.png
 *         disable-logo-fallback-image: false
 *         default-language: zh-cn
 *         noindex-page-titles: "草稿,沙盒"
 *         enable-auto-description: true
 *         try-clean-auto-description: true
 *         google-site-verification-key: "..."
 * </pre>
 *
 * 所有键都是可选的，未配置时使用与 MediaWiki 原版一致的默认值。
 *
 * @param metadataGenerators         元数据生成器（逗号分隔；可用 OpenGraph / Twitter / SchemaOrg / Citation /
 *                                   DublinCore；为空 = 默认 OpenGraph, Twitter, SchemaOrg）
 * @param twitterSiteHandle          全局 twitter:site（设置后页面上的 twitter_site 参数被忽略）
 * @param twitterCardType            twitter:card 类型（默认 summary_large_image）
 * @param defaultImage               默认图片（本地文件名或 URL；页面没有 image 参数时使用）
 * @param disableLogoFallbackImage   关闭「站点 Logo 兜底图片」（对应 $wgWikiSeoDisableLogoFallbackImage）
 * @param defaultLanguage            默认语言（给当前页额外输出一条 hreflang alternate）
 * @param noindexPageTitles          需要 noindex 的页面标题（逗号分隔）
 * @param enableAutoDescription      开启自动描述（本机化实现，替代原版依赖的 TextExtracts）
 * @param tryCleanAutoDescription    自动描述截到最后一个句号（对应 $wgWikiSeoTryCleanAutoDescription）
 * @param googleSiteVerificationKey  Google Search Console 验证码
 * @param bingSiteVerificationKey    Bing 网站管理员验证码
 * @param facebookAppId              Facebook Insights App ID
 * @param facebookAdmins             Facebook Insights 管理员 ID（逗号分隔）
 * @param yandexSiteVerificationKey  Yandex Webmasters 验证码
 * @param pinterestSiteVerificationKey Pinterest 验证码
 * @param nortonSiteVerificationKey  Norton Safe Web 验证码
 * @param naverSiteVerificationKey   Naver 验证码
 * @param baiduSiteVerificationKey   百度验证码
 * @param overwritePageImage         对应 $wgWikiSeoOverwritePageImage；WikiKernel 没有 PageImages，保留但不生效
 */
public record WikiSeoConfig(
        List<String> metadataGenerators,
        String twitterSiteHandle,
        String twitterCardType,
        String defaultImage,
        boolean disableLogoFallbackImage,
        String defaultLanguage,
        List<String> noindexPageTitles,
        boolean enableAutoDescription,
        boolean tryCleanAutoDescription,
        String googleSiteVerificationKey,
        String bingSiteVerificationKey,
        String facebookAppId,
        String facebookAdmins,
        String yandexSiteVerificationKey,
        String pinterestSiteVerificationKey,
        String nortonSiteVerificationKey,
        String naverSiteVerificationKey,
        String baiduSiteVerificationKey,
        boolean overwritePageImage) {

    /** 默认生成器（对应 $wgMetadataGenerators 为空时的默认值）。 */
    public static final List<String> DEFAULT_GENERATORS = List.of("OpenGraph", "Twitter", "SchemaOrg");

    /** 默认 twitter:card（对应 $wgTwitterCardType）。 */
    public static final String DEFAULT_TWITTER_CARD = "summary_large_image";

    public WikiSeoConfig {
        metadataGenerators = metadataGenerators == null || metadataGenerators.isEmpty()
                ? DEFAULT_GENERATORS
                : List.copyOf(metadataGenerators);
        twitterSiteHandle = orEmpty(twitterSiteHandle);
        twitterCardType = twitterCardType == null || twitterCardType.isBlank()
                ? DEFAULT_TWITTER_CARD
                : twitterCardType.trim();
        defaultImage = orEmpty(defaultImage);
        defaultLanguage = orEmpty(defaultLanguage);
        noindexPageTitles = noindexPageTitles == null ? List.of() : List.copyOf(noindexPageTitles);
        googleSiteVerificationKey = orEmpty(googleSiteVerificationKey);
        bingSiteVerificationKey = orEmpty(bingSiteVerificationKey);
        facebookAppId = orEmpty(facebookAppId);
        facebookAdmins = orEmpty(facebookAdmins);
        yandexSiteVerificationKey = orEmpty(yandexSiteVerificationKey);
        pinterestSiteVerificationKey = orEmpty(pinterestSiteVerificationKey);
        nortonSiteVerificationKey = orEmpty(nortonSiteVerificationKey);
        naverSiteVerificationKey = orEmpty(naverSiteVerificationKey);
        baiduSiteVerificationKey = orEmpty(baiduSiteVerificationKey);
    }

    /** 从插件配置读取（键为 kebab-case）。 */
    public static WikiSeoConfig from(PluginConfig config) {
        return new WikiSeoConfig(
                config.getList("metadata-generators"),
                config.get("twitter-site-handle"),
                config.get("twitter-card-type"),
                config.get("default-image"),
                config.getBoolean("disable-logo-fallback-image", false),
                config.get("default-language"),
                config.getList("noindex-page-titles"),
                config.getBoolean("enable-auto-description", false),
                config.getBoolean("try-clean-auto-description", false),
                config.get("google-site-verification-key"),
                config.get("bing-site-verification-key"),
                config.get("facebook-app-id"),
                config.get("facebook-admins"),
                config.get("yandex-site-verification-key"),
                config.get("pinterest-site-verification-key"),
                config.get("norton-site-verification-key"),
                config.get("naver-site-verification-key"),
                config.get("baidu-site-verification-key"),
                // 不生效，仅为与原版配置一一对应
                config.getBoolean("overwrite-page-image", false));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
