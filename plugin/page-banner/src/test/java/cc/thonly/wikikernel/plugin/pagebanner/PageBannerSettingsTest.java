package cc.thonly.wikikernel.plugin.pagebanner;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.config;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerSettings}：轮播配置（{@code interval} / {@code transition} / {@code controls} /
 * {@code pause-on-hover}）的解析与默认值，以及「站点设置 → 插件配置 → 默认值」的读取顺序。
 */
class PageBannerSettingsTest {

    /** 默认值：5 秒 / fade / 显示控件 / 悬停暂停。 */
    @Test
    void carouselDefaults() {
        PageBannerSettings settings = settings(Map.of());

        assertEquals(5, settings.interval());
        assertEquals(PageBannerSettings.DEFAULT_INTERVAL, settings.interval());
        assertEquals("fade", settings.transition());
        assertTrue(settings.controls());
        assertTrue(settings.pauseOnHover());

        PageBannerSettings.CarouselOptions options = settings.carousel();
        assertEquals(5, options.intervalSeconds());
        assertEquals("fade", options.transition());
        assertTrue(options.controls());
        assertTrue(options.pauseOnHover());
    }

    /** 站点设置键 features.page-banner.*（与 core 的站点设置 / LocalSettings.yaml 一致）。 */
    @Test
    void siteSettingsDriveTheCarousel() {
        PageBannerSettings settings = settings(config(
                PageBannerSettings.INTERVAL_KEY, "9",
                PageBannerSettings.TRANSITION_KEY, "slide",
                PageBannerSettings.CONTROLS_KEY, "false",
                PageBannerSettings.PAUSE_ON_HOVER_KEY, "off"));

        assertEquals(9, settings.interval());
        assertEquals("slide", settings.transition());
        assertFalse(settings.controls());
        assertFalse(settings.pauseOnHover());
        // core 的站点设置归一化：true / 1 / on / yes 都算真
        assertTrue(settings(config(PageBannerSettings.CONTROLS_KEY, "on")).controls());
        assertTrue(settings(config(PageBannerSettings.CONTROLS_KEY, "1")).controls());
        assertFalse(settings(config(PageBannerSettings.PAUSE_ON_HOVER_KEY, "off")).pauseOnHover());
        assertTrue(settings(config(PageBannerSettings.PAUSE_ON_HOVER_KEY, "yes")).pauseOnHover());
        assertFalse(settings(config(PageBannerSettings.PAUSE_ON_HOVER_KEY, "0")).pauseOnHover());
        assertEquals(0, settings(config(PageBannerSettings.INTERVAL_KEY, "0")).interval(), "0 = 不自动翻页");
    }

    /** 插件自己的配置（wikikernel.plugins.settings.page-banner.*，短键）优先于站点设置。 */
    @Test
    void pluginConfigOverridesSiteSettings() {
        PageBannerSettings settings = settings(config(
                PageBannerSettings.INTERVAL_KEY, "9",
                PageBannerSettings.TRANSITION_KEY, "slide",
                PageBannerSettings.CONTROLS_KEY, "true",
                PageBannerSettings.PAUSE_ON_HOVER_KEY, "true"), config(
                PageBannerSettings.PLUGIN_INTERVAL_KEY, "12",
                PageBannerSettings.PLUGIN_TRANSITION_KEY, "fade",
                PageBannerSettings.PLUGIN_CONTROLS_KEY, "false",
                PageBannerSettings.PLUGIN_PAUSE_ON_HOVER_KEY, "false"));

        assertEquals(12, settings.interval());
        assertEquals("fade", settings.transition());
        assertFalse(settings.controls());
        assertFalse(settings.pauseOnHover());
    }

    /** 只写了插件配置时照样生效（站点设置为空）。 */
    @Test
    void pluginConfigAloneIsEnough() {
        PageBannerSettings settings = settings(Map.of(), config(
                PageBannerSettings.PLUGIN_INTERVAL_KEY, "20",
                PageBannerSettings.PLUGIN_TRANSITION_KEY, "slide"));

        assertEquals(20, settings.interval());
        assertEquals("slide", settings.transition());
        assertTrue(settings.controls(), "没写的键仍用默认值");
    }

    /** 非法 / 越界值：间隔回退默认（负数按 0）、过渡回退 fade。 */
    @Test
    void invalidCarouselValuesFallBack() {
        assertEquals(PageBannerSettings.DEFAULT_INTERVAL,
                settings(config(PageBannerSettings.INTERVAL_KEY, "abc")).interval());
        assertEquals(PageBannerSettings.DEFAULT_INTERVAL,
                settings(config(PageBannerSettings.INTERVAL_KEY, "  ")).interval());
        assertEquals(0, settings(config(PageBannerSettings.INTERVAL_KEY, "-4")).interval(),
                "负数 = 不自动翻页");
        assertEquals(PageBannerSettings.MAX_INTERVAL,
                settings(config(PageBannerSettings.INTERVAL_KEY, "999999")).interval(), "过大的值截到上限");

        assertEquals("fade", settings(config(PageBannerSettings.TRANSITION_KEY, "bogus")).transition());
        assertEquals("slide", settings(config(PageBannerSettings.TRANSITION_KEY, " SLIDE ")).transition());
        assertEquals(List.of("fade", "slide"), PageBannerSettings.TRANSITION_MODES);
    }

    /** 逐页配置里可以给一个页面写多个文件名（该页固定的轮播顺序）。 */
    @Test
    void perPageCarriesSeveralFiles() {
        PageBannerSettings settings = settings(config(
                PageBannerSettings.PER_PAGE_KEY, "演示页=甲.png | 乙.png\n裸标题页"));

        assertEquals("甲.png | 乙.png", settings.perPage().get("演示页"));
        assertEquals("", settings.perPage().get("裸标题页"));
    }
}
