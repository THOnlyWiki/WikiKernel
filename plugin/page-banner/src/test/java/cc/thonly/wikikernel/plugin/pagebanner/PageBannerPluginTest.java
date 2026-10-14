package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.plugin.pagebanner.hook.ExcludeBannerTag;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerDirective;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerHook;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.config;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.displayContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerPlugin}：插件入口注册的扩展点与消息包（含轮播设置 / 控件文案）。
 */
class PageBannerPluginTest {

    @Test
    void descriptorMatchesThePortedExtension() {
        PageBannerPlugin plugin = new PageBannerPlugin();
        assertEquals("page-banner", plugin.descriptor().id());
        assertEquals("WikidataPageBanner", plugin.descriptor().name());
        assertEquals(PageBannerSettings.ENABLED_KEY, PageBannerPlugin.ENABLED_KEY);
        assertEquals("features.page-banner", PageBannerPlugin.ENABLED_KEY);
    }

    @Test
    void onStartRegistersDirectiveTagHookAndMessages() {
        PageBannerFakes.FakePluginContext context = new PageBannerFakes.FakePluginContext();
        // 单元测试里没有 Spring：siteConfig / fileStore / messageSource 为 null 时插件仍能装配（开关默认开启）
        new PageBannerPlugin().onStart(context);

        assertEquals(1, context.lookup(ParserFunction.class).size());
        ParserFunction function = context.lookup(ParserFunction.class).get(0);
        assertInstanceOf(PageBannerDirective.class, function);
        assertEquals("PAGEBANNER", function.name());

        assertEquals(1, context.lookup(ParserTag.class).size());
        ParserTag tag = context.lookup(ParserTag.class).get(0);
        assertInstanceOf(ExcludeBannerTag.class, tag);
        assertEquals("exclude-banner-page", tag.name());

        assertEquals(1, context.lookup(PageDisplayHook.class).size());
        assertInstanceOf(PageBannerHook.class, context.lookup(PageDisplayHook.class).get(0));

        assertEquals(1, context.lookup(MessageBundle.class).size());
        Map<String, Map<String, String>> messages = context.lookup(MessageBundle.class).get(0).messages();
        assertTrue(messages.get("zh").containsKey("pageBanner.rotation.stable"), messages.toString());
        assertTrue(messages.get("en").containsKey("pageBanner.rotation.daily"), messages.toString());
        // 轮播设置项与控件文案也随插件消息包提供（中英双语）
        assertTrue(messages.get("zh").containsKey("pageBanner.interval"), messages.toString());
        assertTrue(messages.get("en").containsKey("pageBanner.transitionHint"), messages.toString());
        assertTrue(messages.get("zh").containsKey("pageBanner.prev"), messages.toString());
        assertTrue(messages.get("en").containsKey("pageBanner.region"), messages.toString());
    }

    /** 插件配置（wikikernel.plugins.settings.page-banner.*）经 PluginContext 进入 PageBannerSettings。 */
    @Test
    void pluginConfigDrivesTheCarouselSettings() {
        PageBannerSettings settings = PageBannerFakes.settings(Map.of(), config(
                PageBannerSettings.PLUGIN_INTERVAL_KEY, "11",
                PageBannerSettings.PLUGIN_TRANSITION_KEY, "slide",
                PageBannerSettings.PLUGIN_CONTROLS_KEY, "false",
                PageBannerSettings.PLUGIN_PAUSE_ON_HOVER_KEY, "false"));

        assertEquals(11, settings.interval());
        assertEquals("slide", settings.transition());
        assertFalse(settings.controls());
        assertFalse(settings.pauseOnHover());
        assertEquals(new PageBannerSettings.CarouselOptions(11, "slide", false, false), settings.carousel());
    }

    /** 钩子的 head 片段：多图页注入轮播脚本，单图页只注入样式。 */
    @Test
    void hookInjectsTheCarouselAssetsOnlyForSeveralImages() {
        PageBannerHook hook = new PageBannerHook(PageBannerFakes.renderer(
                config(PageBannerSettings.FILES_KEY, "甲.png|乙.png",
                        PageBannerSettings.WHITELIST_ENABLED_KEY, "false"),
                PageBannerFakes.image("甲.png"), PageBannerFakes.image("乙.png")));

        List<String> items = hook.headItems(displayContext("轮播页", Map.of()));
        assertTrue(String.join("\n", items).contains(PageBannerRenderer.SCRIPT), items.toString());
        String html = hook.beforeTitle(displayContext("轮播页", Map.of()));
        assertTrue(html != null && html.contains(PageBannerRenderer.CAROUSEL_ATTRIBUTE), String.valueOf(html));
    }
}
