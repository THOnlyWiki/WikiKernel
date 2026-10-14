package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Map;

/**
 * WikiSEO 的安装向导入口（写入 {@code wikikernel.plugins.settings.wiki-seo.*}）。
 * <p>
 * 所有字段都是可选的：留空表示使用与 MediaWiki 原版一致的默认值。
 * 标签 / 说明走插件自己的消息包（{@code i18n/messages*.properties} 的 {@code setup.wiki-seo.*} 键）。
 */
public class WikiSeoSetup implements SetupStep {

    private final WikiSeoConfig current;
    private final MessageSource messageSource;

    public WikiSeoSetup(WikiSeoConfig current, MessageSource messageSource) {
        this.current = current;
        this.messageSource = messageSource;
    }

    @Override
    public String id() {
        return "wiki-seo";
    }

    @Override
    public String displayName() {
        return "WikiSEO";
    }

    @Override
    public String description() {
        return text("setup.wiki-seo.description");
    }

    @Override
    public int order() {
        return 130;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.text("metadata-generators", text("setup.wiki-seo.metadata-generators"),
                        String.join(",", current.metadataGenerators())),
                SetupField.text("twitter-site-handle", text("setup.wiki-seo.twitter-site-handle"),
                        current.twitterSiteHandle()),
                SetupField.text("twitter-card-type", text("setup.wiki-seo.twitter-card-type"),
                        current.twitterCardType()),
                SetupField.text("default-image", text("setup.wiki-seo.default-image"),
                        current.defaultImage()),
                SetupField.checkbox("disable-logo-fallback-image",
                        text("setup.wiki-seo.disable-logo-fallback-image"),
                        current.disableLogoFallbackImage()),
                SetupField.text("default-language", text("setup.wiki-seo.default-language"),
                        current.defaultLanguage()),
                SetupField.text("noindex-page-titles", text("setup.wiki-seo.noindex-page-titles"),
                        String.join(",", current.noindexPageTitles())),
                SetupField.checkbox("enable-auto-description",
                        text("setup.wiki-seo.enable-auto-description"),
                        current.enableAutoDescription()),
                SetupField.checkbox("try-clean-auto-description",
                        text("setup.wiki-seo.try-clean-auto-description"),
                        current.tryCleanAutoDescription()),
                SetupField.text("google-site-verification-key", text("setup.wiki-seo.google-site-verification-key"),
                        current.googleSiteVerificationKey()),
                SetupField.text("bing-site-verification-key", text("setup.wiki-seo.bing-site-verification-key"),
                        current.bingSiteVerificationKey()),
                SetupField.text("facebook-app-id", text("setup.wiki-seo.facebook-app-id"),
                        current.facebookAppId()),
                SetupField.text("facebook-admins", text("setup.wiki-seo.facebook-admins"),
                        current.facebookAdmins()),
                SetupField.text("yandex-site-verification-key", text("setup.wiki-seo.yandex-site-verification-key"),
                        current.yandexSiteVerificationKey()),
                SetupField.text("pinterest-site-verification-key",
                        text("setup.wiki-seo.pinterest-site-verification-key"),
                        current.pinterestSiteVerificationKey()),
                SetupField.text("norton-site-verification-key", text("setup.wiki-seo.norton-site-verification-key"),
                        current.nortonSiteVerificationKey()),
                SetupField.text("naver-site-verification-key", text("setup.wiki-seo.naver-site-verification-key"),
                        current.naverSiteVerificationKey()),
                SetupField.text("baidu-site-verification-key", text("setup.wiki-seo.baidu-site-verification-key"),
                        current.baiduSiteVerificationKey()),
                SetupField.checkbox("overwrite-page-image",
                        text("setup.wiki-seo.overwrite-page-image"),
                        current.overwritePageImage()));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        context.putPluginSetting("metadata-generators", values.getOrDefault("metadata-generators", "").trim());
        context.putPluginSetting("twitter-site-handle", values.getOrDefault("twitter-site-handle", "").trim());
        context.putPluginSetting("twitter-card-type", values.getOrDefault("twitter-card-type", "").trim());
        context.putPluginSetting("default-image", values.getOrDefault("default-image", "").trim());
        context.putPluginSetting("disable-logo-fallback-image",
                checkbox(values, "disable-logo-fallback-image", false));
        context.putPluginSetting("default-language", values.getOrDefault("default-language", "").trim());
        context.putPluginSetting("noindex-page-titles", values.getOrDefault("noindex-page-titles", "").trim());
        context.putPluginSetting("enable-auto-description", checkbox(values, "enable-auto-description", false));
        context.putPluginSetting("try-clean-auto-description", checkbox(values, "try-clean-auto-description", false));
        context.putPluginSetting("google-site-verification-key",
                values.getOrDefault("google-site-verification-key", "").trim());
        context.putPluginSetting("bing-site-verification-key",
                values.getOrDefault("bing-site-verification-key", "").trim());
        context.putPluginSetting("facebook-app-id", values.getOrDefault("facebook-app-id", "").trim());
        context.putPluginSetting("facebook-admins", values.getOrDefault("facebook-admins", "").trim());
        context.putPluginSetting("yandex-site-verification-key",
                values.getOrDefault("yandex-site-verification-key", "").trim());
        context.putPluginSetting("pinterest-site-verification-key",
                values.getOrDefault("pinterest-site-verification-key", "").trim());
        context.putPluginSetting("norton-site-verification-key",
                values.getOrDefault("norton-site-verification-key", "").trim());
        context.putPluginSetting("naver-site-verification-key",
                values.getOrDefault("naver-site-verification-key", "").trim());
        context.putPluginSetting("baidu-site-verification-key",
                values.getOrDefault("baidu-site-verification-key", "").trim());
        context.putPluginSetting("overwrite-page-image", checkbox(values, "overwrite-page-image", false));
        return SetupResult.ok(text("setup.wiki-seo.saved"));
    }

    /** 取一条本地化文案（缺失时显示键名，见 {@link WikiSeoMessages#text}）。 */
    private String text(String key) {
        return WikiSeoMessages.text(messageSource, key);
    }

    private static String checkbox(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? Boolean.toString(fallback) : Boolean.toString("true".equalsIgnoreCase(value));
    }
}
